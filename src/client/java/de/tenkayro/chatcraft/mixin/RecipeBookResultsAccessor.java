package de.tenkayro.chatcraft.mixin;

import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.client.gui.widget.ToggleButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(RecipeBookResults.class)
public interface RecipeBookResultsAccessor {
    @Accessor("resultButtons")
    List<AnimatedResultButton> chatcraft$getResultButtons();

    @Accessor("nextPageButton")
    ToggleButtonWidget chatcraft$getNextPageButton();

    @Accessor("prevPageButton")
    ToggleButtonWidget chatcraft$getPrevPageButton();

    @Accessor("alternatesWidget")
    RecipeAlternativesWidget chatcraft$getAlternatesWidget();
}
