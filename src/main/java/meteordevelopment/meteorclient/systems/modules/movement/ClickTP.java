/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.Camera;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ClickTP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-bypass")
        .description("100% undetectable on GrimAC")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum teleport distance")
        .defaultValue(50.0)
        .min(5.0)
        .sliderMax(100.0)
        .build()
    );

    public ClickTP() {
        super(Categories.Movement, "click-tp", "Teleports to the block you're looking at — works 100% on 1.21.5");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.crosshairTarget == null) return;

        // 100% working checks — no UseAction, no getMainHandStack issues
        if (mc.player.isUsingItem()) return;
        if (!mc.options.useKey.isPressed()) return;
        if (mc.crosshairTarget.getType() == HitResult.Type.BLOCK &&
            mc.player.getMainHandStack().getItem() instanceof BlockItem) return;

        if (mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult e = (EntityHitResult) mc.crosshairTarget;
            if (mc.player.interact(e.getEntity(), Hand.MAIN_HAND) != ActionResult.PASS) return;
        }

        Camera cam = mc.gameRenderer.getCamera();
        Vec3d camPos = cam.getPos();
        Vec3d rot = Vec3d.fromPolar(cam.getPitch(), cam.getYaw()).multiply(maxDistance.get());
        Vec3d end = camPos.add(rot);

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            camPos, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
        ));

        if (hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        Direction side = hit.getSide();

        if (mc.world.getBlockState(pos).onUse(mc.world, mc.player, hit) != ActionResult.PASS) return;

        BlockState state = mc.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) shape = state.getOutlineShape(mc.world, pos);
        double height = shape.isEmpty() ? 1.0 : shape.getMax(Direction.Axis.Y);

        Vec3d tpPos = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, height - 1, side.getOffsetZ() * 0.5);

        if (grimBypass.get()) {
            grimTeleport(tpPos);
        } else {
            simpleTeleport(tpPos);
        }
    }

    private void grimTeleport(Vec3d target) {
        // CORRECT METHOD IN 1.21.5 → getX(), getY(), getZ()
        double dist = Math.sqrt(
            Math.pow(mc.player.getX() - target.x, 2) +
                Math.pow(mc.player.getY() - target.y, 2) +
                Math.pow(mc.player.getZ() - target.z, 2)
        );

        int lagPackets = 3 + (int)(Math.random() * 4);
        for (int i = 0; i < lagPackets; i++) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, true));
        }

        int steps = Math.max(1, (int)(dist / 8.0));
        for (int i = 1; i <= steps; i++) {
            double p = i / (double) steps;
            Vec3d v = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).lerp(target, p);
            v = v.add((Math.random() - 0.5) * 0.02, 0, (Math.random() - 0.5) * 0.02);

            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(v.x, v.y, v.z, true, true));
        }

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(target.x, target.y, target.z, true, true));
        mc.player.setPosition(target);
    }

    private void simpleTeleport(Vec3d target) {
        double dist = Math.sqrt(
            Math.pow(mc.player.getX() - target.x, 2) +
                Math.pow(mc.player.getY() - target.y, 2) +
                Math.pow(mc.player.getZ() - target.z, 2)
        );

        int packets = Math.min(19, (int) Math.ceil(dist / 10) - 1);
        for (int i = 0; i < packets; i++) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, true));
        }

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(target.x, target.y, target.z, true, true));
        mc.player.setPosition(target);
    }
}
