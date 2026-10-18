package de.tenkayro.chatcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.identity.ClientIdentity;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public final class ChatCraftCommands {
    private ChatCraftCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("chatcraft")
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
                        context.getSource().sendFeedback(Text.literal(accepted ? "ChatCraft: queued " + command : "ChatCraft: unknown command"));
                        return accepted ? 1 : 0;
                    })))
                .then(ClientCommandManager.literal("clearqueue").executes(context -> {
                    ChatCraftClient.queue().clear();
                    ChatCraftClient.input().releaseAll();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: queue cleared and keys released"));
                    return 1;
                }))
            );
            dispatcher.register(ClientCommandManager.literal("nick")
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(context -> {
                        String name = StringArgumentType.getString(context, "name");
                        if (!ClientIdentity.isValidName(name)) {
                            context.getSource().sendError(Text.literal(
                                    "ChatCraft: nickname must contain 1-16 letters, numbers, or underscores"));
                            return 0;
                        }
                        ChatCraftClient.identity().setNickname(name);
                        context.getSource().sendFeedback(Text.literal(
                                "ChatCraft: client-side nickname set to " + name));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    ChatCraftClient.identity().clearNickname();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: client-side nickname cleared"));
                    return 1;
                }))
            );
            dispatcher.register(ClientCommandManager.literal("skin")
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("username", StringArgumentType.word()).executes(context -> {
                        String username = StringArgumentType.getString(context, "username");
                        if (!ChatCraftClient.identity().setSkin(username)) {
                            context.getSource().sendError(Text.literal(
                                    "ChatCraft: Minecraft username must contain 1-16 letters, numbers, or underscores"));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal(
                                "ChatCraft: loading skin from " + username + "…"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    ChatCraftClient.identity().clearSkin();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: client-side skin cleared"));
                    return 1;
                }))
            );
        });
    }
}
