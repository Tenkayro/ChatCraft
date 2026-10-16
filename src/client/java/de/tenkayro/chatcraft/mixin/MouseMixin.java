package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.input.BackgroundModeController;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow private double x;
    @Shadow private double y;

    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalMovement(long window, double newX, double newY, CallbackInfo ci) {
        if (!BackgroundModeController.isChatOnly()) return;
        if (Math.abs(newX - x) > 0.5 || Math.abs(newY - y) > 0.5) {
            GLFW.glfwSetCursorPos(window, x, y);
        }
        ci.cancel();
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalButtons(long window, int button, int action,
                                             int modifiers, CallbackInfo ci) {
        if (BackgroundModeController.isChatOnly()) ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalScroll(long window, double horizontal,
                                            double vertical, CallbackInfo ci) {
        if (BackgroundModeController.isChatOnly()) ci.cancel();
    }
}
