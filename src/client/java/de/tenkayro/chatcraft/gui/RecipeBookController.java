package de.tenkayro.chatcraft.gui;

import de.tenkayro.chatcraft.mixin.RecipeBookResultsAccessor;
import de.tenkayro.chatcraft.mixin.RecipeBookWidgetAccessor;
import de.tenkayro.chatcraft.mixin.RecipeAlternativesWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
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
        book.refresh();
        RecipeBookResultsAccessor area = results(book);
        ClickableWidget button = next ? area.chatcraft$getNextPageButton() : area.chatcraft$getPrevPageButton();
        if (button == null || !button.visible || !button.active || !click(book, button)) {
            return "skipped: no " + (next ? "next" : "previous") + " recipe page";
        }
        return next ? "next recipe page" : "previous recipe page";
    }

    public String selectCategory(MinecraftClient client, int oneBasedIndex) {
        RecipeBookWidget book = openBook(client);
        if (book == null) return "skipped: recipe book is not ready";
        book.refresh();
        List<RecipeGroupButtonWidget> buttons = ((RecipeBookWidgetAccessor) book).chatcraft$getTabButtons()
                .stream().filter(button -> button.visible && button.active).toList();
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= buttons.size()) return "skipped: invalid recipe category";
        if (!click(book, buttons.get(index))) return "skipped: category is not available";
        return "selected recipe category " + oneBasedIndex;
    }

    public String selectAlternative(MinecraftClient client, int oneBasedIndex) {
        RecipeBookWidget book = openBook(client);
        if (book == null) return "skipped: recipe book is not ready";
        RecipeAlternativesWidget alternatives = results(book).chatcraft$getAlternatesWidget();
        if (alternatives == null || !alternatives.isVisible()) return "skipped: no recipe variants are open";
        List<ClickableWidget> buttons = ((RecipeAlternativesWidgetAccessor) alternatives)
                .chatcraft$getAlternativeButtons().stream()
                .filter(button -> button.visible && button.active).toList();
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= buttons.size()) return "skipped: invalid recipe variant";
        ClickableWidget button = buttons.get(index);
        if (!click(book, button)) {
            return "skipped: recipe variant is not available";
        }
        return "selected recipe variant " + oneBasedIndex;
    }

    public String toggleFilter(MinecraftClient client) {
        RecipeBookWidget book = openBook(client);
        if (book == null) return "skipped: recipe book is not ready";
        ClickableWidget button = ((RecipeBookWidgetAccessor) book).chatcraft$getToggleCraftableButton();
        if (button == null || !button.visible || !button.active || !click(book, button)) {
            return "skipped: recipe filter is not available";
        }
        return "toggled craftable recipe filter";
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
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        MinecraftMouseController.moveTo(x, y);
        return book.mouseClicked(x, y, 0);
    }

    private static boolean isUsable(AnimatedResultButton button) {
        return button.visible && button.active
                && button.getResultCollection() != null
                && button.hasResults();
    }
}
