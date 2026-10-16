package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiButtonMapper;
import de.tenkayro.chatcraft.gui.MinecraftMouseController;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ClickableWidget;

public final class ScreenOverlayRenderer {
    private static final float LABEL_SCALE = 0.65F;
    private static final GuiButtonMapper BUTTONS = new GuiButtonMapper();

    private ScreenOverlayRenderer() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register(ScreenOverlayRenderer::render));
    }

    private static void render(Screen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftMouseController.update();
        if (!ChatCraftConfig.get().overlayEnabled || screen instanceof GameMenuScreen) return;
        if (screen instanceof HandledScreen<?> handled) SlotOverlayRenderer.render(handled, context);
        if (screen instanceof InventoryScreen inventory) RecipeBookOverlayRenderer.render(inventory, context);
        renderButtons(screen, context);
    }

    private static void renderButtons(Screen screen, DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 1_000);
        try {
            for (GuiButtonMapper.LabelledButton item : BUTTONS.map(screen)) {
                drawLabel(context, client, item.button(), item.label());
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void drawLabel(DrawContext context, MinecraftClient client,
                                  ClickableWidget button, String label) {
        int textWidth = client.textRenderer.getWidth(label);
        int labelWidth = Math.round((textWidth + 3) * LABEL_SCALE);
        int x = Math.max(0, Math.min(button.getX(), client.getWindow().getScaledWidth() - labelWidth));
        int y = Math.max(0, Math.min(button.getY(), client.getWindow().getScaledHeight() - 7));
        context.fill(x, y, x + labelWidth, y + 7, 0xE8000000);
        context.getMatrices().push();
        context.getMatrices().translate(x + 1, y, 1);
        context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
        context.drawTextWithShadow(client.textRenderer, label, 0, 0, 0x55FF55);
        context.getMatrices().pop();
    }
}
