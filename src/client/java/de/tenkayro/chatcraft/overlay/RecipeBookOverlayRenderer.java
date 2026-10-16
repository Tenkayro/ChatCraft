package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.mixin.RecipeAlternativesWidgetAccessor;
import de.tenkayro.chatcraft.mixin.RecipeBookResultsAccessor;
import de.tenkayro.chatcraft.mixin.RecipeBookWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;

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
            int categoryIndex = 0;
            for (RecipeGroupButtonWidget button : ((RecipeBookWidgetAccessor) book).chatcraft$getTabButtons()) {
                if (!button.visible || !button.active) continue;
                drawLabel(context, client, button, "T" + (++categoryIndex));
            }
            int visibleIndex = 0;
            for (AnimatedResultButton button : buttons) {
                if (!button.visible || !button.active
                        || button.getResultCollection() == null
                        || !button.hasResults()) continue;
                drawLabel(context, client, button, "R" + (++visibleIndex));
            }
            RecipeBookResultsAccessor resultAccess = (RecipeBookResultsAccessor) area;
            drawVisibleLabel(context, client, resultAccess.chatcraft$getPrevPageButton(), "RP");
            drawVisibleLabel(context, client, resultAccess.chatcraft$getNextPageButton(), "RN");
            RecipeAlternativesWidget alternatives = resultAccess.chatcraft$getAlternatesWidget();
            if (alternatives != null && alternatives.isVisible()) {
                int alternativeIndex = 0;
                for (ClickableWidget button : ((RecipeAlternativesWidgetAccessor) alternatives)
                        .chatcraft$getAlternativeButtons()) {
                    if (!button.visible || !button.active) continue;
                    drawLabel(context, client, button, "V" + (++alternativeIndex));
                }
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void drawVisibleLabel(DrawContext context, MinecraftClient client,
                                         ClickableWidget button, String label) {
        if (button != null && button.visible && button.active) drawLabel(context, client, button, label);
    }

    private static void drawLabel(DrawContext context, MinecraftClient client,
                                  ClickableWidget button, String label) {
        int width = client.textRenderer.getWidth(label);
        int x = button.getX();
        int y = button.getY();
        context.fill(x, y, x + Math.round((width + 3) * LABEL_SCALE), y + 7, 0xD8000000);
        context.getMatrices().push();
        context.getMatrices().translate(x + 1, y, 1);
        context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
        context.drawTextWithShadow(client.textRenderer, label, 0, 0, 0x55FF55);
        context.getMatrices().pop();
    }
}
