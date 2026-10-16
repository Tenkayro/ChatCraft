package de.tenkayro.chatcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.tenkayro.chatcraft.ChatCraftClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public final class ChatCraftCommands {
    private ChatCraftCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("chatcraft")
                .then(ClientCommandManager.literal("connect").executes(context -> {
                    ChatCraftClient.twitch().connect();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: connection requested"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("disconnect").executes(context -> {
                    ChatCraftClient.twitch().disconnect();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: disconnected"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(context -> {
                    context.getSource().sendFeedback(Text.literal("ChatCraft: " + ChatCraftClient.twitch().detail()
                        + ", queued=" + ChatCraftClient.queue().size() + ", dashboard="
                        + ChatCraftClient.dashboard().detail()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("dashboard").executes(context -> {
                    context.getSource().sendFeedback(ChatCraftClient.dashboardMessage());
                    return ChatCraftClient.dashboard().running() ? 1 : 0;
                }))
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("command", StringArgumentType.greedyString()).executes(context -> {
                        String command = StringArgumentType.getString(context, "command");
                        boolean accepted = ChatCraftClient.commands().submit("local-test", command, true);
                        context.getSource().sendFeedback(Text.literal(accepted ? "ChatCraft: queued " + command : "ChatCraft: unknown command or queue full"));
                        return accepted ? 1 : 0;
                    })))
                .then(ClientCommandManager.literal("clearqueue").executes(context -> {
                    ChatCraftClient.queue().clear();
                    ChatCraftClient.input().releaseAll();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: queue cleared and keys released"));
                    return 1;
                }))
        ));
    }
}
