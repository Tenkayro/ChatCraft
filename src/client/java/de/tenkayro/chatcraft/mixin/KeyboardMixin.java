package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.input.BackgroundModeController;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalKeys(long window, int key, int scanCode,
                                          int action, int modifiers, CallbackInfo ci) {
        if (BackgroundModeController.isChatOnly() && key != GLFW.GLFW_KEY_F8) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalCharacters(long window, int codePoint,
                                                int modifiers, CallbackInfo ci) {
        if (BackgroundModeController.isChatOnly()) ci.cancel();
    }
}
