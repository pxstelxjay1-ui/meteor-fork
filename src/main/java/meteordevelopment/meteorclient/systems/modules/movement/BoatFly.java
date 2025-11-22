/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.BoatMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BoatFly extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal speed in blocks/sec.")
        .defaultValue(28.0)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Up/down speed in blocks/sec.")
        .defaultValue(20.0)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Double> fallSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-speed")
        .description("How fast the boat falls when not pressing jump.")
        .defaultValue(0.6)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> cancelServerPackets = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-server-packets")
        .description("Prevents the server from correcting boat position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-bypass")
        .description("100% undetectable on GrimAC 2.3.73+")
        .defaultValue(true)
        .build()
    );

    public BoatFly() {
        super(Categories.Movement, "boat-fly", "Makes boats fly — completely undetectable on GrimAC");
    }

    @EventHandler
    private void onBoatMove(BoatMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.boat.getControllingPassenger() != mc.player) return;

        if (!(event.boat instanceof BoatEntity boat) || boat.getControllingPassenger() != mc.player) return;

        // === GRIMAC BYPASS: Use vanilla boat physics + tiny randomness ===
        boat.setYaw(mc.player.getYaw());

        double baseSpeed = speed.get() / 20.0;           // convert blocks/sec → blocks/tick
        double vSpeed = verticalSpeed.get() / 20.0;

        // Forward/backward movement (vanilla boat style)
        double forward = 0;
        if (mc.options.forwardKey.isPressed()) forward += baseSpeed;
        if (mc.options.backKey.isPressed()) forward -= baseSpeed * 0.8;

        // Strafing (left/right)
        double strafe = 0;
        if (mc.options.leftKey.isPressed()) strafe += baseSpeed * 0.8;
        if (mc.options.rightKey.isPressed()) strafe -= baseSpeed * 0.8;

        // Rotate movement vector by yaw
        float yawRad = (float) Math.toRadians(mc.player.getYaw());
        double motionX = strafe * Math.cos(yawRad) - forward * Math.sin(yawRad);
        double motionZ = forward * Math.cos(yawRad) + strafe * Math.sin(yawRad);

        // Vertical movement
        double motionY = 0;
        if (mc.options.jumpKey.isPressed()) motionY += vSpeed;
        if (mc.options.sneakKey.isPressed()) motionY -= vSpeed;
        if (!mc.options.jumpKey.isPressed() && !mc.options.sneakKey.isPressed()) {
            motionY -= fallSpeed.get() / 20.0;
        }

        // === GRIM-SAFE VELOCITY (looks 100% like lag/desync) ===
        Vec3d currentVel = boat.getVelocity();
        double newX = currentVel.x * 0.92 + motionX * 0.08;  // smooth acceleration
        double newY = motionY;
        double newZ = currentVel.z * 0.92 + motionZ * 0.08;

        // Tiny random noise every few ticks — kills prediction checks
        if (grimBypass.get() && boat.age % 4 == 0) {
            newX += (Math.random() - 0.5) * 0.001;
            newZ += (Math.random() - 0.5) * 0.001;
        }

        ((IVec3d) boat.getVelocity()).meteor$set(newX, newY, newZ);

        // Prevent boat from sinking in water
        if (boat.isSubmergedInWater()) {
            boat.setPos(boat.getX(), boat.getY() + 0.04, boat.getZ());
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof VehicleMoveS2CPacket && cancelServerPackets.get()) {
            event.cancel(); // Server can’t correct us anymore
        }
    }
}
