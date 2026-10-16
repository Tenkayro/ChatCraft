package de.tenkayro.chatcraft.config;

import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

public final class ChatCraftConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget channel;
    private TextFieldWidget username;
    private TextFieldWidget token;
    private TextFieldWidget prefix;
    private TextFieldWidget movement;
    private TextFieldWidget camera;
    private TextFieldWidget queue;
    private TextFieldWidget cooldown;
    private boolean controls;
    private boolean overlay;
    private boolean autoConnect;
    private boolean autoReconnect;

    public ChatCraftConfigScreen(Screen parent) {
        super(Text.literal("ChatCraft Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ChatCraftConfig config = ChatCraftConfig.get();
        controls = config.controlsEnabled;
        overlay = config.overlayEnabled;
        autoConnect = config.autoConnect;
        autoReconnect = config.autoReconnect;
        int left = width / 2 - 155;
        int right = width / 2 + 5;
        channel = field(left, 42, "Twitch channel", config.channel, 128);
        username = field(right, 42, "Twitch username", config.username, 128);
        token = field(left, 74, "OAuth/access token", config.oauthToken, 512);
        token.setRenderTextProvider((value, offset) -> OrderedText.styledForwardsVisitedString("•".repeat(value.length()), Style.EMPTY));
        prefix = field(right, 74, "Command prefix (optional)", config.commandPrefix, 16);
        movement = field(left, 106, "Movement ticks", Integer.toString(config.movementDurationTicks), 4);
        camera = field(right, 106, "Camera step degrees", Float.toString(config.cameraStepDegrees), 6);
        queue = field(left, 138, "Queue size", Integer.toString(config.queueSize), 5);
        cooldown = field(right, 138, "Cooldown milliseconds", Long.toString(config.cooldownMillis), 6);

        addDrawableChild(toggle(left, 170, 150, () -> "Controls: " + onOff(controls), button -> {
            controls = !controls;
            button.setMessage(Text.literal("Controls: " + onOff(controls)));
        }));
        addDrawableChild(toggle(right, 170, 150, () -> "Slot overlay: " + onOff(overlay), button -> {
            overlay = !overlay;
            button.setMessage(Text.literal("Slot overlay: " + onOff(overlay)));
        }));
        addDrawableChild(toggle(left, 194, 150, () -> "Auto-connect: " + onOff(autoConnect), button -> {
            autoConnect = !autoConnect;
            button.setMessage(Text.literal("Auto-connect: " + onOff(autoConnect)));
        }));
        addDrawableChild(toggle(right, 194, 150, () -> "Auto-reconnect: " + onOff(autoReconnect), button -> {
            autoReconnect = !autoReconnect;
            button.setMessage(Text.literal("Auto-reconnect: " + onOff(autoReconnect)));
        }));
        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Done"), button -> saveAndClose())
            .dimensions(left, height - 24, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), button -> {
            save();
            ChatCraftClient.twitch().connect();
            button.setMessage(Text.literal("Requested"));
        }).dimensions(left + 106, height - 24, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), button -> ChatCraftClient.twitch().disconnect())
            .dimensions(right + 54, height - 24, 96, 20).build());
    }

    private TextFieldWidget field(int x, int y, String label, String value, int maxLength) {
        TextFieldWidget widget = new TextFieldWidget(textRenderer, x, y, 150, 20, Text.literal(label));
        widget.setText(value);
        widget.setMaxLength(maxLength);
        widget.setPlaceholder(Text.literal(label));
        addDrawableChild(widget);
        return widget;
    }

    private ButtonWidget toggle(int x, int y, int width, java.util.function.Supplier<String> label, ButtonWidget.PressAction action) {
        return ButtonWidget.builder(Text.literal(label.get()), action).dimensions(x, y, width, 20).build();
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }

    private void saveAndClose() {
        save();
        close();
    }

    private void save() {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.channel = channel.getText();
        config.username = username.getText();
        config.oauthToken = token.getText();
        config.commandPrefix = prefix.getText();
        config.controlsEnabled = controls;
        config.overlayEnabled = overlay;
        config.autoConnect = autoConnect;
        config.autoReconnect = autoReconnect;
        config.movementDurationTicks = parseInt(movement.getText(), config.movementDurationTicks);
        config.cameraStepDegrees = parseFloat(camera.getText(), config.cameraStepDegrees);
        config.queueSize = parseInt(queue.getText(), config.queueSize);
        config.cooldownMillis = parseLong(cooldown.getText(), config.cooldownMillis);
        config.save();
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        int left = width / 2 - 155;
        int right = width / 2 + 5;
        context.drawTextWithShadow(textRenderer, "Twitch channel", left, 31, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Twitch username (needed with token)", right, 31, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "OAuth/access token (hidden)", left, 63, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Command prefix (optional)", right, 63, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Movement duration (ticks)", left, 95, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Camera step (degrees)", right, 95, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Queue size", left, 127, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Cooldown (milliseconds)", right, 127, 0xA0A0A0);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
