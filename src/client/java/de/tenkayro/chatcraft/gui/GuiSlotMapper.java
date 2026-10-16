package de.tenkayro.chatcraft.gui;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GuiSlotMapper {
    public record LabelledSlot(String label, Slot slot) { }

    public List<LabelledSlot> map(HandledScreen<?> screen) {
        List<Slot> slots = screen.getScreenHandler().slots.stream().filter(Slot::isEnabled).toList();
        List<Integer> columns = slots.stream().map(slot -> slot.x).distinct().sorted().toList();
        List<Integer> rows = slots.stream().map(slot -> slot.y).distinct().sorted().toList();
        List<LabelledSlot> result = new ArrayList<>();
        for (Slot slot : slots) {
            String label = columnName(columns.indexOf(slot.x)) + (rows.indexOf(slot.y) + 1);
            result.add(new LabelledSlot(label, slot));
        }
        result.sort(Comparator.comparingInt((LabelledSlot item) -> item.slot().y).thenComparingInt(item -> item.slot().x));
        return result;
    }

    public Slot find(HandledScreen<?> screen, String label) {
        String wanted = label.toUpperCase(Locale.ROOT);
        return map(screen).stream().filter(item -> item.label().equals(wanted)).map(LabelledSlot::slot).findFirst().orElse(null);
    }

    private static String columnName(int index) {
        StringBuilder value = new StringBuilder();
        int number = index + 1;
        while (number > 0) {
            number--;
            value.append((char) ('A' + number % 26));
            number /= 26;
        }
        return value.reverse().toString();
    }
}
