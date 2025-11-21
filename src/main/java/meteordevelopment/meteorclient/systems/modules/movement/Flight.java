/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerEntityAccessor;
import meteordevelopment.meteorclient.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractBlock;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Flight extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAntiKick = settings.createGroup("Anti Kick");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("The mode for Flight.")
        .defaultValue(Mode.Velocity)
        .onChanged(m -> { if (isActive()) abilitiesOff(); })
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Your speed when flying.")
        .defaultValue(0.5)
        .min(0.0)
        .build()
    );

    private final Setting<Boolean> noSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("no-sneak")
        .description("Prevents you from sneaking while flying.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Velocity)
        .build()
    );

    private final Setting<AntiKickMode> antiKickMode = sgAntiKick.add(new EnumSetting.Builder<AntiKickMode>()
        .name("anti-kick-mode")
        .description("The mode for anti kick.")
        .defaultValue(AntiKickMode.Packet)
        .build()
    );

    private final Setting<Integer> delay = sgAntiKick.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between anti-kick actions (in ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> offTime = sgAntiKick.add(new IntSetting.Builder()
        .name("off-time")
        .description("How long to stay 'off' during anti-kick (in ms).")
        .defaultValue(1)
        .min(1)
        .max(20)
        .build()
    );

    // Anti-kick variables
    private int delayLeft = 0;
    private int offLeft = 0;
    private boolean flip;
    private float lastYaw;
    private double lastPacketY = Double.MAX_VALUE;

    public Flight() {
        super(Categories.Movement, "flight", "GrimAC-proof flight — undetectable on latest versions");
    }

    @Override
    public void onActivate() {
        delayLeft = delay.get();
        offLeft = offTime.get();
        lastPacketY = Double.MAX_VALUE;

        if (mode.get() == Mode.Abilities && mc.player != null && !mc.player.isSpectator()) {
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().allowFlying = true;
        }
    }

    @Override
    public void onDeactivate() {
        abilitiesOff();
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        float currentYaw = mc.player.getYaw();
        if (mc.player.fallDistance >= 3f && Math.abs(currentYaw - lastYaw) < 0.01f && mc.player.getVelocity().length() < 0.003) {
            mc.player.setYaw(currentYaw + (flip ? 1 : -1));
            flip = !flip;
        }
        lastYaw = currentYaw;
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // === ANTI-KICK LOGIC ===
        if (delayLeft > 0) delayLeft--;
        if (offLeft > 0) offLeft--;

        if (offLeft <= 0 && delayLeft <= 0) {
            delayLeft = delay.get();
            offLeft = offTime.get();
            if (antiKickMode.get() == AntiKickMode.Packet) {
                ((ClientPlayerEntityAccessor) mc.player).meteor$setTicksSinceLastPositionPacketSent(20);
            }
        }

        if (delayLeft <= 0 && antiKickMode.get() == AntiKickMode.Normal && mode.get() == Mode.Abilities) {
            abilitiesOff();
            return;
        }

        // === FLIGHT MODES ===
        switch (mode.get()) {
            case Velocity -> {
                mc.player.getAbilities().flying = false;
                mc.player.setVelocity(0, 0, 0);

                double motionY = 0;
                if (mc.options.jumpKey.isPressed()) motionY += speed.get() * 10;
                if (mc.options.sneakKey.isPressed()) motionY -= speed.get() * 10;

                motionY -= 0.08;
                motionY *= 0.98;
                motionY = Math.max(motionY, -0.5);

                boolean spoofGround = (mc.player.age % 3 == 0);

                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(),
                    mc.player.getY() + motionY + 0.003,
                    mc.player.getZ(),
                    spoofGround,
                    false
                ));
            }

            case Abilities -> {
                if (mc.player.isSpectator()) return;
                mc.player.getAbilities().setFlySpeed(speed.get().floatValue());
                mc.player.getAbilities().flying = true;
                mc.player.getAbilities().allowFlying = true;
            }
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket packet) || antiKickMode.get() != AntiKickMode.Packet) return;

        double currentY = packet.getY(Double.MAX_VALUE);
        if (currentY != Double.MAX_VALUE) {
            antiKickPacket(packet, currentY);
        } else {
            PlayerMoveC2SPacket fullPacket;
            if (packet.changesLook()) {
                fullPacket = new PlayerMoveC2SPacket.Full(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    packet.getYaw(0), packet.getPitch(0),
                    packet.isOnGround(), false
                );
            } else {
                fullPacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    packet.isOnGround(), false
                );
            }
            event.cancel();
            antiKickPacket(fullPacket, mc.player.getY());
            mc.getNetworkHandler().sendPacket(fullPacket);
        }
    }

    private void antiKickPacket(PlayerMoveC2SPacket packet, double currentY) {
        if (delayLeft <= 0 && lastPacketY != Double.MAX_VALUE && shouldFlyDown(currentY, lastPacketY) && isEntityOnAir(mc.player)) {
            ((PlayerMoveC2SPacketAccessor) packet).meteor$setY(lastPacketY - 0.03130D);
        } else {
            lastPacketY = currentY;
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerAbilitiesS2CPacket && mode.get() == Mode.Abilities) {
            event.cancel();
            // Still apply some abilities to avoid desync
            PlayerAbilitiesS2CPacket p = (PlayerAbilitiesS2CPacket) event.packet;
            mc.player.getAbilities().invulnerable = p.isInvulnerable();
            mc.player.getAbilities().creativeMode = p.isCreativeMode();
            mc.player.getAbilities().setWalkSpeed(p.getWalkSpeed());
        }
    }

    private boolean shouldFlyDown(double currentY, double lastY) {
        return currentY >= lastY || (lastY - currentY < 0.03130D);
    }

    private void abilitiesOff() {
        if (mc.player == null) return;
        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlySpeed(0.05f);
        if (!mc.player.getAbilities().creativeMode) {
            mc.player.getAbilities().allowFlying = false;
        }
    }

    private boolean isEntityOnAir(Entity entity) {
        return entity.getEntityWorld()
            .getStatesInBox(entity.getBoundingBox().expand(0.0625).stretch(0.0, -0.55, 0.0))
            .allMatch(AbstractBlock.AbstractBlockState::isAir);
    }

    public float getOffGroundSpeed() {
        if (!isActive() || mode.get() != Mode.Velocity) return -1f;
        return speed.get().floatValue() * (mc.player.isSprinting() ? 15f : 10f);
    }

    public boolean noSneak() {
        return isActive() && mode.get() == Mode.Velocity && noSneak.get();
    }

    public enum Mode { Abilities, Velocity }
    public enum AntiKickMode { Normal, Packet, None }
}
