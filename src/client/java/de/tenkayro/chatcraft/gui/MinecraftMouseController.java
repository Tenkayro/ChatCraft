package de.tenkayro.chatcraft.gui;

import de.tenkayro.chatcraft.mixin.MouseAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

public final class MinecraftMouseController {
    private static final long MOVE_DURATION_NANOS = 160_000_000L;
    private static Move move;

    private MinecraftMouseController() { }

    public static void moveTo(double scaledX, double scaledY) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        double clampedX = Math.max(0.0, Math.min(scaledX, window.getScaledWidth() - 1.0));
        double clampedY = Math.max(0.0, Math.min(scaledY, window.getScaledHeight() - 1.0));
        double currentX = client.mouse.getX() * window.getScaledWidth() / window.getWidth();
        double currentY = client.mouse.getY() * window.getScaledHeight() / window.getHeight();
        move = new Move(currentX, currentY, clampedX, clampedY, System.nanoTime());
    }

    public static void update() {
        Move current = move;
        if (current == null) return;
        float progress = Math.min(1.0F,
                (float) (System.nanoTime() - current.startedAt) / MOVE_DURATION_NANOS);
        float eased = 1.0F - (float) Math.pow(1.0F - progress, 3.0);
        setPosition(lerp(current.startX, current.targetX, eased),
                lerp(current.startY, current.targetY, eased));
        if (progress >= 1.0F) move = null;
    }

    private static void setPosition(double scaledX, double scaledY) {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        double windowX = scaledX * window.getWidth() / window.getScaledWidth();
        double windowY = scaledY * window.getHeight() / window.getScaledHeight();
        ((MouseAccessor) client.mouse).chatcraft$setX(windowX);
        ((MouseAccessor) client.mouse).chatcraft$setY(windowY);
        GLFW.glfwSetCursorPos(window.getHandle(), windowX, windowY);
    }

    private static double lerp(double start, double end, float progress) {
        return start + (end - start) * progress;
    }

    private record Move(double startX, double startY, double targetX, double targetY,
                        long startedAt) { }
}
