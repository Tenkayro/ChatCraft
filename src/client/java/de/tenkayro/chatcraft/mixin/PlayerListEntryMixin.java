package de.tenkayro.chatcraft.mixin;

import com.mojang.authlib.GameProfile;
import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow @Final private GameProfile profile;

    @Inject(method = "getProfile", at = @At("HEAD"), cancellable = true)
    private void chatcraft$useClientNicknameProfile(CallbackInfoReturnable<GameProfile> callback) {
        if (!isLocalEntry()) return;
        callback.setReturnValue(ChatCraftClient.identity().nicknameProfile(profile));
    }

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void chatcraft$useClientNicknameDisplay(CallbackInfoReturnable<Text> callback) {
        if (!isLocalEntry()) return;
        String nickname = ChatCraftClient.identity().nickname();
        if (!nickname.isBlank()) callback.setReturnValue(Team.decorateName(getOriginalTeam(), Text.literal(nickname)));
    }

    @Inject(method = "getScoreboardTeam", at = @At("HEAD"), cancellable = true)
    private void chatcraft$keepOriginalScoreboardTeam(CallbackInfoReturnable<Team> callback) {
        if (isLocalEntry()) callback.setReturnValue(getOriginalTeam());
    }

    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void chatcraft$useClientSkin(CallbackInfoReturnable<SkinTextures> callback) {
        if (!isLocalEntry()) return;
        SkinTextures textures = ChatCraftClient.identity().skinTextures();
        if (textures != null) callback.setReturnValue(textures);
    }

    private Team getOriginalTeam() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        Scoreboard scoreboard = client.world.getScoreboard();
        return scoreboard.getScoreHolderTeam(profile.getName());
    }

    private boolean isLocalEntry() {
        return ChatCraftClient.identity() != null && ChatCraftClient.identity().isLocalProfile(profile);
    }
}
