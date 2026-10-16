package de.tenkayro.chatcraft;

import net.fabricmc.api.ClientModInitializer;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.command.ChatCraftCommands;
import de.tenkayro.chatcraft.command.CommandParser;
import de.tenkayro.chatcraft.command.CommandService;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.input.InputController;
import de.tenkayro.chatcraft.twitch.TwitchConnection;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "chatcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static ActionQueue queue;
    private static CommandService commands;
    private static TwitchConnection twitch;
    private static InputController input;

    @Override
    public void onInitializeClient() {
        ChatCraftConfig config = ChatCraftConfig.get();
        queue = new ActionQueue();
        commands = new CommandService(new CommandParser(), queue);
        twitch = new TwitchConnection(commands, queue);
        input = new InputController(queue);
        ClientTickEvents.END_CLIENT_TICK.register(input::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> input.releaseAll());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            twitch.disconnect();
            input.releaseAll();
        });
        ChatCraftCommands.register();
        if (config.autoConnect) twitch.connect();
        LOGGER.info("ChatCraft initialized");
    }

    public static ActionQueue queue() { return queue; }
    public static CommandService commands() { return commands; }
    public static TwitchConnection twitch() { return twitch; }
    public static InputController input() { return input; }
}
