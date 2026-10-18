package de.tenkayro.chatcraft.camera;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

public final class CameraController {
    private static final long TURN_DURATION_NANOS = 300_000_000L;
    private float startYaw;
    private float startPitch;
    private float targetYaw;
    private float targetPitch;
    private long startedAt;
    private boolean turning;

    public void turn(MinecraftClient client, float yawDelta, float pitchDelta) {
        if (client.player == null) return;
        long now = System.nanoTime();
        update(client, now);
        float baseYaw = turning ? targetYaw : client.player.getYaw();
        float basePitch = turning ? targetPitch : client.player.getPitch();
        startYaw = client.player.getYaw();
        startPitch = client.player.getPitch();
        targetYaw = baseYaw + yawDelta;
        targetPitch = MathHelper.clamp(basePitch + pitchDelta, -90.0f, 90.0f);
        startedAt = now;
        turning = true;
    }

    public void tick(MinecraftClient client) {
        update(client, System.nanoTime());
    }

    public void renderFrame(MinecraftClient client) {
        update(client, System.nanoTime());
    }

    private void update(MinecraftClient client, long now) {
        if (client.player == null) {
            turning = false;
            return;
        }
        if (!turning) return;

        float progress = MathHelper.clamp((float) (now - startedAt) / TURN_DURATION_NANOS, 0.0F, 1.0F);
        float eased = progress * progress * (3.0F - 2.0F * progress);
        float yaw = startYaw + MathHelper.wrapDegrees(targetYaw - startYaw) * eased;
        float pitch = MathHelper.lerp(eased, startPitch, targetPitch);

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
        client.player.prevYaw = yaw;
        client.player.prevPitch = pitch;
        if (progress >= 1.0F) turning = false;
    }

    public boolean isTurning() {
        return turning;
    }

    public void cancel() {
        turning = false;
    }
}
