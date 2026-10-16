package de.tenkayro.chatcraft.gui;

import de.tenkayro.chatcraft.mixin.MouseAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

public final class MinecraftMouseController {
    private MinecraftMouseController() { }

    public static void moveTo(double scaledX, double scaledY) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        double clampedX = Math.max(0.0, Math.min(scaledX, window.getScaledWidth() - 1.0));
        double clampedY = Math.max(0.0, Math.min(scaledY, window.getScaledHeight() - 1.0));
        double windowX = clampedX * window.getWidth() / window.getScaledWidth();
        double windowY = clampedY * window.getHeight() / window.getScaledHeight();

        ((MouseAccessor) client.mouse).chatcraft$setX(windowX);
        ((MouseAccessor) client.mouse).chatcraft$setY(windowY);
        GLFW.glfwSetCursorPos(window.getHandle(), windowX, windowY);
    }
}
