package de.tenkayro.chatcraft.mixin;

import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AnimatedResultButton.class)
public interface AnimatedResultButtonAccessor {
    @Accessor("resultCollection")
    RecipeResultCollection chatcraft$getResultCollection();
}
