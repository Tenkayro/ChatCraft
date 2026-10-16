package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

public final class SlotOverlayRenderer {
    private static final float LABEL_SCALE = 0.75F;
    private static final long MOVE_DURATION_NANOS = 180_000_000L;
    private static final long CLICK_DURATION_NANOS = 350_000_000L;
    private static final long VISIBLE_DURATION_NANOS = 2_500_000_000L;
    private static final GuiSlotMapper MAPPER = new GuiSlotMapper();
    private static CursorState cursor;

    private SlotOverlayRenderer() { }

    public static void render(HandledScreen<?> screen, DrawContext context, int originX, int originY) {
        if (!ChatCraftConfig.get().overlayEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 500);
        try {
            for (GuiSlotMapper.LabelledSlot item : MAPPER.map(screen)) {
                int x = originX + item.slot().x;
                int y = originY + item.slot().y;
                int width = client.textRenderer.getWidth(item.label());
                int scaledWidth = Math.round((width + 2) * LABEL_SCALE);
                context.fill(x, y, x + scaledWidth + 1, y + 7, 0xE0000000);
                context.getMatrices().push();
                context.getMatrices().translate(x + 1, y, 1);
                context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
                context.drawTextWithShadow(client.textRenderer, item.label(), 0, 0, 0x55FF55);
                context.getMatrices().pop();
            }
            renderVirtualCursor(screen, context, client, originX, originY);
        } finally {
            context.getMatrices().pop();
        }
    }

    public static void moveCursorTo(HandledScreen<?> screen, Slot slot) {
        long now = System.nanoTime();
        float targetX = slot.x + 8.0F;
        float targetY = slot.y + 8.0F;
        float startX = targetX;
        float startY = targetY;
        if (cursor != null && cursor.screen == screen) {
            float progress = progress(now, cursor.startedAt);
            startX = lerp(cursor.startX, cursor.targetX, progress);
            startY = lerp(cursor.startY, cursor.targetY, progress);
        }
        cursor = new CursorState(screen, slot.id, startX, startY, targetX, targetY, now);
    }

    private static void renderVirtualCursor(HandledScreen<?> screen, DrawContext context, MinecraftClient client,
                                            int originX, int originY) {
        CursorState state = cursor;
        long now = System.nanoTime();
        if (state == null || state.screen != screen || now - state.startedAt > VISIBLE_DURATION_NANOS) return;

        float progress = progress(now, state.startedAt);
        int cursorX = originX + Math.round(lerp(state.startX, state.targetX, progress));
        int cursorY = originY + Math.round(lerp(state.startY, state.targetY, progress));

        Slot target = screen.getScreenHandler().slots.stream()
                .filter(slot -> slot.id == state.slotId)
                .findFirst()
                .orElse(null);
        if (target != null) {
            int left = originX + target.x - 1;
            int top = originY + target.y - 1;
            int color = now - state.startedAt < CLICK_DURATION_NANOS ? 0xFFFFFF55 : 0xFF55FF55;
            drawBorder(context, left, top, 18, 18, color);
        }

        ItemStack cursorStack = screen.getScreenHandler().getCursorStack();
        if (!cursorStack.isEmpty()) {
            int itemX = cursorX + 10;
            int itemY = cursorY + 7;
            context.fill(itemX - 1, itemY - 1, itemX + 17, itemY + 17, 0xE0000000);
            drawBorder(context, itemX - 1, itemY - 1, 18, 18, 0xFF55FF55);
            context.drawItemInSlot(client.textRenderer, cursorStack, itemX, itemY);
        }
        drawPointer(context, cursorX, cursorY);
    }

    private static void drawPointer(DrawContext context, int x, int y) {
        int[] widths = {1, 2, 3, 4, 5, 6, 7, 8, 6, 4, 4, 3};
        for (int row = 0; row < widths.length; row++) {
            context.fill(x, y + row, x + widths[row] + 2, y + row + 1, 0xFF101010);
            if (row > 0 && row < 9 && widths[row] > 2) {
                context.fill(x + 1, y + row, x + widths[row], y + row + 1, 0xFFFFFFFF);
            }
        }
        context.fill(x + 4, y + 8, x + 7, y + 14, 0xFF101010);
        context.fill(x + 5, y + 8, x + 6, y + 13, 0xFFFFFFFF);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static float progress(long now, long startedAt) {
        return Math.min(1.0F, (float) (now - startedAt) / MOVE_DURATION_NANOS);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private record CursorState(HandledScreen<?> screen, int slotId, float startX, float startY,
                               float targetX, float targetY, long startedAt) { }
}
