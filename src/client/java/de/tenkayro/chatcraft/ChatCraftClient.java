package de.tenkayro.chatcraft;

import net.fabricmc.api.ClientModInitializer;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.command.ChatCraftCommands;
import de.tenkayro.chatcraft.command.CommandParser;
import de.tenkayro.chatcraft.command.CommandService;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import de.tenkayro.chatcraft.dashboard.ChatCraftDashboardServer;
import de.tenkayro.chatcraft.input.InputController;
import de.tenkayro.chatcraft.input.BackgroundModeController;
import de.tenkayro.chatcraft.twitch.TwitchConnection;
import de.tenkayro.chatcraft.twitch.TwitchEventSub;
import de.tenkayro.chatcraft.twitch.TwitchChatFeed;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "chatcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static ActionQueue queue;
    private static CommandService commands;
    private static TwitchConnection twitch;
    private static TwitchEventSub eventSub;
    private static TwitchChatFeed chatFeed;
    private static InputController input;
    private static BackgroundModeController backgroundMode;
    private static ActivityLog activity;
    private static ChatCraftDashboardServer dashboard;

    @Override
    public void onInitializeClient() {
        ChatCraftConfig config = ChatCraftConfig.get();
        activity = new ActivityLog();
        queue = new ActionQueue();
        CommandParser parser = new CommandParser();
        commands = new CommandService(parser, queue, activity);
        chatFeed = new TwitchChatFeed(parser);
        eventSub = new TwitchEventSub(queue, activity);
        twitch = new TwitchConnection(commands, queue, activity, chatFeed, eventSub);
        input = new InputController(queue, activity);
        backgroundMode = new BackgroundModeController(input);
        dashboard = new ChatCraftDashboardServer(activity);
        dashboard.start();
        ClientTickEvents.END_CLIENT_TICK.register(input::tick);
        ClientTickEvents.END_CLIENT_TICK.register(backgroundMode::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) client.player.sendMessage(dashboardMessage(), false);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> input.releaseAll());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            twitch.disconnect();
            chatFeed.close();
            input.releaseAll();
            dashboard.stop();
        });
        ChatCraftCommands.register();
        if (config.autoConnect) twitch.connect();
        LOGGER.info("ChatCraft initialized");
    }

    public static ActionQueue queue() { return queue; }
    public static CommandService commands() { return commands; }
    public static TwitchConnection twitch() { return twitch; }
    public static TwitchEventSub eventSub() { return eventSub; }
    public static TwitchChatFeed chatFeed() { return chatFeed; }
    public static InputController input() { return input; }
    public static ActivityLog activity() { return activity; }
    public static ChatCraftDashboardServer dashboard() { return dashboard; }

    public static Text dashboardMessage() {
        if (!dashboard.running()) return Text.literal("ChatCraft dashboard: " + dashboard.detail())
            .formatted(Formatting.RED);
        String dashboardUrl = dashboard.baseUrl();
        MutableText link = Text.literal(dashboardUrl).styled(style -> style
            .withColor(Formatting.AQUA)
            .withUnderline(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, dashboardUrl)));
        MutableText message = Text.literal("ChatCraft dashboard: ").append(link);
        String lanUrl = dashboard.lanUrl();
        if (!lanUrl.isBlank()) {
            MutableText lanLink = Text.literal(lanUrl).styled(style -> style
                .withColor(Formatting.GREEN)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, lanUrl)));
            message.append(Text.literal(" | LAN: ")).append(lanLink);
        }
        return message;
    }
}
