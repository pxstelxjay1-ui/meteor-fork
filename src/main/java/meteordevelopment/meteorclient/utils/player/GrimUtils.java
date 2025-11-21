package meteordevelopment.meteorclient.utils.player;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class GrimUtils {
    public static boolean grimBypass = true; // toggle with a module if you want

    public static float[] getGrimRotations(Vec3d from, Vec3d to, LivingEntity target) {
        double diffX = to.x - from.x;
        double diffY = to.y + target.getEyeHeight(target.getPose()) * 0.9 - (from.y + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = to.z - from.z;

        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, dist));

        // Grim GCD + humanization (0.5–0.8°/tick max delta)
        float gcdFix = (float) (Math.pow(mc.player.getAttackCooldownProgress(0.5f), 2) * 1.2);
        yaw = MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * 0.68f + mc.player.getYaw();
        pitch = MathHelper.clamp(pitch, -90f, 90f);

        // Tiny random offset so Grim doesn’t see perfect aim
        yaw += (float) (Math.random() - 0.5) * 0.25f;
        pitch += (float) (Math.random() - 0.5) * 0.18f;

        return new float[]{yaw, pitch};
    }
}
