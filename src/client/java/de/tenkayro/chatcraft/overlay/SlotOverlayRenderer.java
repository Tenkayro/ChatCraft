package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;

public final class SlotOverlayRenderer {
    private static final GuiSlotMapper MAPPER = new GuiSlotMapper();

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
                context.fill(x, y, x + width + 2, y + 9, 0xE0000000);
                context.drawTextWithShadow(client.textRenderer, item.label(), x + 1, y, 0x55FF55);
            }
            renderCursorStack(screen, context, client);
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void renderCursorStack(HandledScreen<?> screen, DrawContext context, MinecraftClient client) {
        ItemStack cursorStack = screen.getScreenHandler().getCursorStack();
        if (cursorStack.isEmpty()) return;
        int x = 6;
        int y = client.getWindow().getScaledHeight() - 28;
        context.fill(x, y, x + 72, y + 22, 0xE0000000);
        context.drawTextWithShadow(client.textRenderer, "HELD", x + 5, y + 7, 0x55FF55);
        context.drawItemInSlot(client.textRenderer, cursorStack, x + 50, y + 3);
    }
}
