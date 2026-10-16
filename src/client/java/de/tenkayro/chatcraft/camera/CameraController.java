package de.tenkayro.chatcraft.camera;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

public final class CameraController {
    public void turn(MinecraftClient client, float yawDelta, float pitchDelta) {
        if (client.player == null) return;
        client.player.setYaw(client.player.getYaw() + yawDelta);
        client.player.setPitch(MathHelper.clamp(client.player.getPitch() + pitchDelta, -90.0f, 90.0f));
    }
}
