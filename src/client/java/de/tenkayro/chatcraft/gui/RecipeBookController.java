package de.tenkayro.chatcraft.gui;

import de.tenkayro.chatcraft.mixin.RecipeBookResultsAccessor;
import de.tenkayro.chatcraft.mixin.RecipeBookWidgetAccessor;
import de.tenkayro.chatcraft.mixin.AnimatedResultButtonAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.List;

public final class RecipeBookController {
    public String toggle(MinecraftClient client) {
        if (client.player == null) return "skipped: no player";
        boolean openedInventory = !(client.currentScreen instanceof InventoryScreen);
        InventoryScreen screen = inventoryScreen(client);
        if (screen == null) return "skipped: inventory is not ready";
        RecipeBookWidget book = screen.getRecipeBookWidget();
        if (openedInventory) {
            if (!book.isOpen()) book.toggleOpen();
        } else {
            book.toggleOpen();
        }
        return book.isOpen() ? "recipe book opened" : "recipe book closed";
    }

    public String select(MinecraftClient client, int oneBasedIndex) {
        RecipeBookWidget book = openBook(client);
        if (book == null) return "skipped: recipe book is not ready";
        book.refresh();
        List<AnimatedResultButton> buttons = results(book).chatcraft$getResultButtons().stream()
                .filter(RecipeBookController::isUsable)
                .toList();
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= buttons.size()) return "skipped: invalid recipe number";
        AnimatedResultButton button = buttons.get(index);
        if (!click(book, button)) return "skipped: recipe is not visible";
        return "selected recipe " + oneBasedIndex;
    }

    public String changePage(MinecraftClient client, boolean next) {
        RecipeBookWidget book = openBook(client);
        if (book == null) return "skipped: recipe book is not ready";
        RecipeBookResultsAccessor area = results(book);
        ClickableWidget button = next ? area.chatcraft$getNextPageButton() : area.chatcraft$getPrevPageButton();
        if (button == null || !button.visible || !button.active || !click(book, button)) {
            return "skipped: no " + (next ? "next" : "previous") + " recipe page";
        }
        return next ? "next recipe page" : "previous recipe page";
    }

    private static RecipeBookWidget openBook(MinecraftClient client) {
        if (client.player == null) return null;
        InventoryScreen screen = inventoryScreen(client);
        if (screen == null) return null;
        RecipeBookWidget book = screen.getRecipeBookWidget();
        if (!book.isOpen()) book.toggleOpen();
        return book;
    }

    private static InventoryScreen inventoryScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof InventoryScreen)) {
            if (client.player == null) return null;
            client.setScreen(new InventoryScreen(client.player));
        }
        return client.currentScreen instanceof InventoryScreen screen ? screen : null;
    }

    private static RecipeBookResultsAccessor results(RecipeBookWidget book) {
        RecipeBookResults area = ((RecipeBookWidgetAccessor) book).chatcraft$getRecipesArea();
        return (RecipeBookResultsAccessor) area;
    }

    private static boolean click(RecipeBookWidget book, ClickableWidget button) {
        return book.mouseClicked(button.getX() + button.getWidth() / 2.0,
                button.getY() + button.getHeight() / 2.0, 0);
    }

    private static boolean isUsable(AnimatedResultButton button) {
        return button.visible && button.active
                && ((AnimatedResultButtonAccessor) button).chatcraft$getResultCollection() != null
                && button.hasResults();
    }
}
