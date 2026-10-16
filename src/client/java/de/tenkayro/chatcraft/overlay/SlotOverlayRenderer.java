package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

public final class SlotOverlayRenderer {
    private static final GuiSlotMapper MAPPER = new GuiSlotMapper();

    private SlotOverlayRenderer() { }

    public static void render(HandledScreen<?> screen, DrawContext context, int originX, int originY) {
        if (!ChatCraftConfig.get().overlayEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        for (GuiSlotMapper.LabelledSlot item : MAPPER.map(screen)) {
            int x = originX + item.slot().x + 1;
            int y = originY + item.slot().y + 1;
            int width = client.textRenderer.getWidth(item.label());
            context.fill(x - 1, y - 1, x + width + 1, y + 8, 0xB0000000);
            context.drawText(client.textRenderer, item.label(), x, y, 0xFFFFFF, false);
        }
    }
}
