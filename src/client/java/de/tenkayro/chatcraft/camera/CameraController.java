package de.tenkayro.chatcraft.camera;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

public final class CameraController {
    private static final int SMOOTHING_TICKS = 6;
    private float targetYaw;
    private float targetPitch;
    private int remainingTicks;

    public void turn(MinecraftClient client, float yawDelta, float pitchDelta) {
        if (client.player == null) return;
        float baseYaw = remainingTicks > 0 ? targetYaw : client.player.getYaw();
        float basePitch = remainingTicks > 0 ? targetPitch : client.player.getPitch();
        targetYaw = baseYaw + yawDelta;
        targetPitch = MathHelper.clamp(basePitch + pitchDelta, -90.0f, 90.0f);
        remainingTicks = SMOOTHING_TICKS;
    }

    public void tick(MinecraftClient client) {
        if (client.player == null) {
            remainingTicks = 0;
            return;
        }
        if (remainingTicks <= 0) return;
        float factor = remainingTicks == 1 ? 1.0F : 0.38F;
        float yawDifference = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
        client.player.setYaw(client.player.getYaw() + yawDifference * factor);
        client.player.setPitch(MathHelper.lerp(factor, client.player.getPitch(), targetPitch));
        remainingTicks--;
    }
}
