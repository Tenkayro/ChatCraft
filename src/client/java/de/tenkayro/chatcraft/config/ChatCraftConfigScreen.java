package de.tenkayro.chatcraft.config;

import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

public final class ChatCraftConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget port;

    public ChatCraftConfigScreen(Screen parent) {
        super(Text.literal("ChatCraft Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = width / 2;
        port = new TextFieldWidget(textRenderer, center - 100, 64, 200, 20, Text.literal("Dashboard port"));
        port.setText(Integer.toString(ChatCraftConfig.get().dashboardPort));
        port.setMaxLength(5);
        port.setPlaceholder(Text.literal("38473"));
        addDrawableChild(port);

        addDrawableChild(ButtonWidget.builder(Text.literal("Open Web Panel"), button -> openDashboard())
            .dimensions(center - 100, 98, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Done"), button -> {
            save();
            close();
        }).dimensions(center - 100, height - 48, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
            .dimensions(center + 2, height - 48, 98, 20).build());
    }

    private void openDashboard() {
        save();
        if (ChatCraftClient.dashboard().running()) {
            Util.getOperatingSystem().open(ChatCraftClient.dashboard().baseUrl());
        }
    }

    private void save() {
        ChatCraftConfig config = ChatCraftConfig.get();
        int previousPort = config.dashboardPort;
        config.dashboardPort = parsePort(port.getText(), previousPort);
        config.save();
        if (config.dashboardPort != previousPort) ChatCraftClient.dashboard().restart();
    }

    private static int parsePort(String value, int fallback) {
        try {
            return Math.max(1024, Math.min(65535, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            "All ChatCraft settings are managed in the local web panel.", width / 2, 38, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Dashboard port", width / 2 - 100, 53, 0xA0A0A0);
        context.drawCenteredTextWithShadow(textRenderer,
            "Changing the port also changes the Twitch OAuth redirect URL.", width / 2, 126, 0x808080);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
