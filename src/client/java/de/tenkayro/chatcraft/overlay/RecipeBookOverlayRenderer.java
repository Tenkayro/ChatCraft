package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.mixin.RecipeBookResultsAccessor;
import de.tenkayro.chatcraft.mixin.RecipeBookWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;

import java.util.List;

public final class RecipeBookOverlayRenderer {
    private static final float LABEL_SCALE = 0.6F;

    private RecipeBookOverlayRenderer() { }

    public static void render(InventoryScreen screen, DrawContext context) {
        if (!ChatCraftConfig.get().overlayEnabled) return;
        RecipeBookWidget book = screen.getRecipeBookWidget();
        if (!book.isOpen()) return;
        RecipeBookResults area = ((RecipeBookWidgetAccessor) book).chatcraft$getRecipesArea();
        List<AnimatedResultButton> buttons = ((RecipeBookResultsAccessor) area).chatcraft$getResultButtons();
        MinecraftClient client = MinecraftClient.getInstance();
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 650);
        try {
            for (int index = 0; index < buttons.size(); index++) {
                AnimatedResultButton button = buttons.get(index);
                if (!button.hasResults()) continue;
                String label = "RECIPE" + (index + 1);
                int width = client.textRenderer.getWidth(label);
                int x = button.getX();
                int y = button.getY();
                context.fill(x, y, x + Math.round((width + 2) * LABEL_SCALE), y + 7, 0xE0000000);
                context.getMatrices().push();
                context.getMatrices().translate(x + 1, y, 1);
                context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
                context.drawTextWithShadow(client.textRenderer, label, 0, 0, 0x55FF55);
                context.getMatrices().pop();
            }
        } finally {
            context.getMatrices().pop();
        }
    }
}
