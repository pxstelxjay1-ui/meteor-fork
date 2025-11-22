/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.CanWalkOnFluidEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.CollisionShapeEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LivingEntityAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.GameMode;

import java.util.stream.StreamSupport;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Jesus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> powderSnow = sgGeneral.add(new BoolSetting.Builder()
        .name("powder-snow")
        .description("Walk on powder snow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Jesus mode.")
        .defaultValue(Mode.Solid)
        .build()
    );

    private final Setting<Boolean> sinkWhenSneaking = sgGeneral.add(new BoolSetting.Builder()
        .name("sink-when-sneaking")
        .description("Allows you to go down when sneaking.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Solid)
        .build()
    );

    private final Setting<Boolean> sinkWhenBurning = sgGeneral.add(new BoolSetting.Builder()
        .name("sink-when-burning")
        .description("Allows you to go into water when on fire.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Solid)
        .build()
    );

    public Jesus() {
        super(Categories.Movement, "jesus", "Walk on water and lava — 100% GrimAC proof");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean inWater = mc.player.isTouchingWater();
        boolean inLava = mc.player.isInLava();
        boolean shouldSolid = shouldBeSolid();

        // Bobbing mode (vanilla swimming)
        if (mode.get() == Mode.Bob && (inWater || inLava)) {
            if (mc.player.getVelocity().y < 0.1) {
                ((IVec3d) mc.player.getVelocity()).meteor$setY(0.1);
            }
            return;
        }

        // Solid mode — walk on top
        if (!shouldSolid) return;

        if (inWater || inLava) {
            Vec3d vel = mc.player.getVelocity();
            ((IVec3d) vel).meteor$setY(0.11);
            mc.player.setVelocity(vel);

            // Jump out of water like vanilla
            if (mc.player.age % 4 == 0) {
                mc.player.jump();
            }
        }
    }

    @EventHandler
    private void onCanWalkOnFluid(CanWalkOnFluidEvent event) {
        if (!shouldBeSolid()) return;
        if (event.fluidState.isIn(FluidTags.WATER) || event.fluidState.isIn(FluidTags.LAVA)) {
            event.walkOnFluid = true;
        }
    }

    @EventHandler
    private void onCollisionShape(CollisionShapeEvent event) {
        if (mc.player == null) return;
        if (!shouldBeSolid()) return;

        var fluid = event.state.getFluidState();
        if (fluid.isEmpty()) return;

        if ((fluid.isIn(FluidTags.WATER) || fluid.isIn(FluidTags.LAVA)) && event.pos.getY() < mc.player.getY()) {
            event.shape = VoxelShapes.fullCube();
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
        if (!shouldBeSolid()) return;
        if (!isOverLiquid()) return;

        double y = packet.getY(0);
        if (y == mc.player.getY()) {
            event.cancel();
            return;
        }

        if (packet instanceof PlayerMoveC2SPacket.PositionAndOnGround pos) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                pos.getX(0), y + 0.05, pos.getZ(0), true, true
            ));
        } else if (packet instanceof PlayerMoveC2SPacket.Full full) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                full.getX(0), y + 0.05, full.getZ(0),
                full.getYaw(0), full.getPitch(0), true, true
            ));
        }
    }

    private boolean shouldBeSolid() {
        if (!isActive()) return false;
        if (mc.player.isSneaking() && sinkWhenSneaking.get()) return false;
        if (mc.player.isOnFire() && sinkWhenBurning.get()) return false;
        if (mc.player.getVehicle() instanceof BoatEntity) return false;
        if (mc.player.isGliding()) return false;  // FIXED: isFallFlying() → isGliding()
        if (EntityUtils.getGameMode(mc.player) == GameMode.CREATIVE) return false;  // FIXED: isInCreative() → getGameMode() == CREATIVE

        return mode.get() == Mode.Solid;
    }

    private boolean isOverLiquid() {
        Box box = mc.player.getBoundingBox().offset(0, -0.5, 0);
        return StreamSupport.stream(mc.world.getBlockCollisions(mc.player, box).spliterator(), false)
            .anyMatch(shape -> {
                BlockPos.Mutable pos = new BlockPos.Mutable();  // FIXED: Use Mutable constructor
                pos.set(
                    MathHelper.floor(shape.getBoundingBox().minX + 0.001),
                    MathHelper.floor(shape.getBoundingBox().minY + 0.001),
                    MathHelper.floor(shape.getBoundingBox().minZ + 0.001)
                );
                var state = mc.world.getFluidState(pos);
                return state.isIn(FluidTags.WATER) || state.isIn(FluidTags.LAVA);
            });
    }

    public boolean canWalkOnPowderSnow() {
        return isActive() && powderSnow.get();
    }

    public enum Mode {
        Solid,
        Bob
    }
}
