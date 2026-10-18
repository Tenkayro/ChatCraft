package de.tenkayro.chatcraft.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

@Mixin(PlayerListEntry.class)
public interface PlayerListEntryAccessor {
    @Accessor("profile")
    @Mutable
    void chatcraft$setProfile(GameProfile profile);

    @Accessor("texturesSupplier")
    @Mutable
    void chatcraft$setTexturesSupplier(Supplier<SkinTextures> supplier);
}
