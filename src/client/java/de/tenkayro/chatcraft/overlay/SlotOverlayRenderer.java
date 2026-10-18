package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import de.tenkayro.chatcraft.gui.MinecraftMouseController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;

public final class SlotOverlayRenderer {
    private static final float LABEL_SCALE = 0.75F;
    private static final long CLICK_DURATION_NANOS = 350_000_000L;
    private static final Identifier CURSOR_TEXTURE =
            Identifier.of("chatcraft", "textures/gui/cursor.png");
    private static final int CURSOR_TEXTURE_SIZE = 32;
    private static final int CURSOR_RENDER_SIZE = 16;
    private static final int CURSOR_HOTSPOT_X = 1;
    private static final int CURSOR_HOTSPOT_Y = 1;
    private static final GuiSlotMapper MAPPER = new GuiSlotMapper();
    private static CursorState cursor;

    private SlotOverlayRenderer() { }

    public static void render(HandledScreen<?> screen, DrawContext context) {
        if (!ChatCraftConfig.get().overlayEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        de.tenkayro.chatcraft.mixin.HandledScreenAccessor access =
                (de.tenkayro.chatcraft.mixin.HandledScreenAccessor) screen;
        int originX = access.chatcraft$getX();
        int originY = access.chatcraft$getY();
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
        de.tenkayro.chatcraft.mixin.HandledScreenAccessor access =
                (de.tenkayro.chatcraft.mixin.HandledScreenAccessor) screen;
        int targetX = access.chatcraft$getX() + slot.x + 8;
        int targetY = access.chatcraft$getY() + slot.y + 8;
        MinecraftMouseController.moveTo(targetX, targetY);
        cursor = new CursorState(screen, slot.id, targetX, targetY, now);
    }

    private static void renderVirtualCursor(HandledScreen<?> screen, DrawContext context, MinecraftClient client,
                                            int originX, int originY) {
        CursorState state = cursor;
        long now = System.nanoTime();
        if (state == null || state.screen != screen) return;

        int cursorX = (int) Math.round(client.mouse.getX()
                * client.getWindow().getScaledWidth() / client.getWindow().getWidth());
        int cursorY = (int) Math.round(client.mouse.getY()
                * client.getWindow().getScaledHeight() / client.getWindow().getHeight());

        Slot target = screen.getScreenHandler().slots.stream()
                .filter(slot -> slot.id == state.slotId)
                .findFirst()
                .orElse(null);
        if (target != null) {
            int left = originX + target.x - 1;
            int top = originY + target.y - 1;
            if (now - state.startedAt < CLICK_DURATION_NANOS) {
                drawBorder(context, left, top, 18, 18, 0xFFFFFF55);
            }
        }

        drawPointer(context, cursorX, cursorY);
    }

    private static void drawPointer(DrawContext context, int x, int y) {
        context.drawTexture(CURSOR_TEXTURE,
                x - CURSOR_HOTSPOT_X, y - CURSOR_HOTSPOT_Y,
                0.0F, 0.0F,
                CURSOR_RENDER_SIZE, CURSOR_RENDER_SIZE,
                CURSOR_TEXTURE_SIZE, CURSOR_TEXTURE_SIZE);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record CursorState(HandledScreen<?> screen, int slotId, int targetX, int targetY,
                               long startedAt) { }
}
