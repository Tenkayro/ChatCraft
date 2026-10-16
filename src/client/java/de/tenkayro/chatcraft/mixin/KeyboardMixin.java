package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.input.BackgroundModeController;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
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
        if (key == GLFW.GLFW_KEY_F8) {
            if (action == GLFW.GLFW_PRESS) {
                BackgroundModeController.toggle(MinecraftClient.getInstance());
            }
            // F8 is owned by ChatCraft and is handled even while every other key is locked.
            ci.cancel();
            return;
        }
        if (BackgroundModeController.isChatOnly()) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void chatcraft$blockLocalCharacters(long window, int codePoint,
                                                int modifiers, CallbackInfo ci) {
        if (BackgroundModeController.isChatOnly()) ci.cancel();
    }
}
