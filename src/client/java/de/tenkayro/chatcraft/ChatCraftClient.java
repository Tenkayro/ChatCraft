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
import de.tenkayro.chatcraft.twitch.TwitchConnection;
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
    private static InputController input;
    private static ActivityLog activity;
    private static ChatCraftDashboardServer dashboard;

    @Override
    public void onInitializeClient() {
        ChatCraftConfig config = ChatCraftConfig.get();
        activity = new ActivityLog();
        queue = new ActionQueue();
        commands = new CommandService(new CommandParser(), queue, activity);
        twitch = new TwitchConnection(commands, queue, activity);
        input = new InputController(queue, activity);
        dashboard = new ChatCraftDashboardServer(activity);
        dashboard.start();
        ClientTickEvents.END_CLIENT_TICK.register(input::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) client.player.sendMessage(dashboardMessage(), false);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> input.releaseAll());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            twitch.disconnect();
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
    public static InputController input() { return input; }
    public static ActivityLog activity() { return activity; }
    public static ChatCraftDashboardServer dashboard() { return dashboard; }

    public static Text dashboardMessage() {
        if (!dashboard.running()) return Text.literal("ChatCraft dashboard: " + dashboard.detail())
            .formatted(Formatting.RED);
        MutableText link = Text.literal(ChatCraftDashboardServer.BASE_URL).styled(style -> style
            .withColor(Formatting.AQUA)
            .withUnderline(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ChatCraftDashboardServer.BASE_URL)));
        return Text.literal("ChatCraft dashboard: ").append(link);
    }
}
