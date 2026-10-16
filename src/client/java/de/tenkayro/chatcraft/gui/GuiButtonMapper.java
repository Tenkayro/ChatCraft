package de.tenkayro.chatcraft.gui;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GuiButtonMapper {
    public record LabelledButton(String label, ClickableWidget button) { }

    public List<LabelledButton> map(Screen screen) {
        if (screen instanceof GameMenuScreen) return List.of();
        List<ClickableWidget> buttons = new ArrayList<>();
        Set<Element> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element element : screen.children()) collect(element, buttons, visited);
        buttons.removeIf(button -> !button.visible || !button.active
                || button.getWidth() <= 0 || button.getHeight() <= 0
                || button.getRight() <= 0 || button.getBottom() <= 0
                || button.getX() >= screen.width || button.getY() >= screen.height);
        buttons.sort(Comparator.comparingInt(ClickableWidget::getY)
                .thenComparingInt(ClickableWidget::getX)
                .thenComparing(button -> button.getClass().getName()));

        List<LabelledButton> result = new ArrayList<>(buttons.size());
        for (int index = 0; index < buttons.size(); index++) {
            result.add(new LabelledButton("U" + (index + 1), buttons.get(index)));
        }
        return List.copyOf(result);
    }

    public ClickableWidget find(Screen screen, String label) {
        String wanted = label.toUpperCase(Locale.ROOT);
        return map(screen).stream()
                .filter(item -> item.label().equals(wanted))
                .map(LabelledButton::button)
                .findFirst()
                .orElse(null);
    }

    private static void collect(Element element, List<ClickableWidget> result, Set<Element> visited) {
        if (!visited.add(element)) return;
        if (element instanceof ClickableWidget button) result.add(button);
        if (element instanceof ParentElement parent) {
            for (Element child : parent.children()) collect(child, result, visited);
        }
    }
}
