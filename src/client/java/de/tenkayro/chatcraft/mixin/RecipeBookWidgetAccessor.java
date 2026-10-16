package de.tenkayro.chatcraft.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.widget.ToggleButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RecipeBookWidget.class)
public interface RecipeBookWidgetAccessor {
    @Accessor("recipesArea")
    RecipeBookResults chatcraft$getRecipesArea();

    @Accessor("tabButtons")
    java.util.List<RecipeGroupButtonWidget> chatcraft$getTabButtons();

    @Accessor("toggleCraftableButton")
    ToggleButtonWidget chatcraft$getToggleCraftableButton();
}
