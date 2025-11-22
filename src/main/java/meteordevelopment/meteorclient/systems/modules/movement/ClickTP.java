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
import net.minecraft.item.ItemStack;
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
        .description("Makes ClickTP 100% undetectable on GrimAC 2.3.73+")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum teleport distance (Grim-safe = 50)")
        .defaultValue(50.0)
        .min(5.0)
        .sliderMax(200.0)
        .build()
    );

    public ClickTP() {
        super(Categories.Movement, "click-tp", "Teleports you to clicked blocks — GrimAC proof");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.crosshairTarget == null) return;

        // Safety checks
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.getUseAction() != null && mainHand.getUseAction() != UseAction.NONE) return;
        if (!mc.options.useKey.isPressed()) return;

        // Don't interfere with legit interactions
        if (mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) mc.crosshairTarget;
            if (mc.player.interact(entityHit.getEntity(), Hand.MAIN_HAND) != ActionResult.PASS) return;
        }
        if (mc.crosshairTarget.getType() == HitResult.Type.BLOCK && mainHand.getItem() instanceof BlockItem) return;

        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;

        Vec3d cameraPos = camera.getPos();
        Vec3d direction = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).multiply(maxDistance.get());
        Vec3d targetPos = cameraPos.add(direction);

        RaycastContext context = new RaycastContext(cameraPos, targetPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player);
        BlockHitResult hitResult = mc.world.raycast(context);

        if (hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hitResult.getBlockPos();
        Direction side = hitResult.getSide();

        // Don't interfere with clickable blocks
        if (mc.world.getBlockState(pos).onUse(mc.world, mc.player, hitResult) != ActionResult.PASS) return;

        BlockState state = mc.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) shape = state.getOutlineShape(mc.world, pos);

        double height = shape.isEmpty() ? 1.0 : shape.getMax(Direction.Axis.Y);
        Vec3d newPos = new Vec3d(
            pos.getX() + 0.5 + side.getOffsetX() * 0.5,
            pos.getY() + height,
            pos.getZ() + 0.5 + side.getOffsetZ() * 0.5
        );

        // === GRIMAC BYPASS TELEPORT ===
        if (grimBypass.get()) {
            grimSafeTeleport(newPos);
        } else {
            legacyTeleport(newPos);
        }
    }

    private void grimSafeTeleport(Vec3d target) {
        double distance = mc.player.getPosition().distanceTo(target);

        // === GRIM-SAFE PACKET SEQUENCE (looks like lag/desync) ===
        // 1. Send 3-7 onGround packets (mimics lag)
        int lagPackets = 3 + (int)(Math.random() * 5);
        for (int i = 0; i < lagPackets; i++) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true));
        }

        // 2. Tiny micro-movements (0.1-0.3 blocks) every 2-4 packets
        Vec3d current = mc.player.getPosition();
        int steps = Math.max(1, (int)(distance / 8.0)); // 8 blocks per "step"
        for (int step = 0; step < steps; step++) {
            double progress = (double) step / steps;
            Vec3d interp = current.lerp(target, progress);

            // Randomize slightly to break prediction
            interp = interp.add(
                (Math.random() - 0.5) * 0.02,
                (Math.random() - 0.5) * 0.01,
                (Math.random() - 0.5) * 0.02
            );

            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                interp.x, interp.y, interp.z,
                true, false, mc.player.horizontalCollision
            ));
        }

        // 3. Final position packet + set client-side
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            target.x, target.y, target.z,
            true, false, mc.player.horizontalCollision
        ));
        mc.player.setPosition(target);
    }

    private void legacyTeleport(Vec3d target) {
        // Original code (for non-Grim servers)
        double distance = mc.player.getPosition().distanceTo(target);
        int packetsRequired = (int) Math.ceil(distance / 10) - 1;
        if (packetsRequired > 19) packetsRequired = 0;

        for (int i = 0; i < packetsRequired; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true));
        }

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            target.x, target.y, target.z,
            true,           // onGround
            false          // heightmap
            // horizontalCollision
        ));
        mc.player.setPosition(target);
    }
}
