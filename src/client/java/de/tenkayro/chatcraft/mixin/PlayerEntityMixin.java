package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void chatcraft$useClientNickname(CallbackInfoReturnable<Text> callback) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != (Object) this || ChatCraftClient.identity() == null) return;
        String nickname = ChatCraftClient.identity().nickname();
        if (!nickname.isBlank()) callback.setReturnValue(Text.literal(nickname));
    }
}
