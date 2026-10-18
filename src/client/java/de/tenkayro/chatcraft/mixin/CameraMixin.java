package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void chatcraft$updateSmoothCamera(BlockView area, Entity focusedEntity,
                                               boolean thirdPerson, boolean inverseView,
                                               float tickDelta, CallbackInfo ci) {
        if (ChatCraftClient.input() != null) {
            ChatCraftClient.input().renderCameraFrame(MinecraftClient.getInstance());
        }
    }
}
