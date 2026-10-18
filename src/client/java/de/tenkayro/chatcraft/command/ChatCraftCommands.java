package de.tenkayro.chatcraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.identity.ClientIdentity;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public final class ChatCraftCommands {
    private ChatCraftCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("cc")
                .then(ClientCommandManager.literal("connect")
                    .executes(context -> connect(context.getSource(), "all"))
                    .then(ClientCommandManager.literal("twitch")
                        .executes(context -> connect(context.getSource(), "twitch")))
                    .then(ClientCommandManager.literal("youtube")
                        .executes(context -> connect(context.getSource(), "youtube"))))
                .then(ClientCommandManager.literal("disconnect")
                    .executes(context -> disconnect(context.getSource(), "all"))
                    .then(ClientCommandManager.literal("twitch")
                        .executes(context -> disconnect(context.getSource(), "twitch")))
                    .then(ClientCommandManager.literal("youtube")
                        .executes(context -> disconnect(context.getSource(), "youtube"))))
                .then(ClientCommandManager.literal("status").executes(context -> {
                    context.getSource().sendFeedback(Text.literal("ChatCraft: Twitch="
                            + ChatCraftClient.twitch().detail() + ", YouTube="
                            + ChatCraftClient.youtube().detail() + ", queued="
                            + ChatCraftClient.queue().size() + ", dashboard="
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
                        context.getSource().sendFeedback(Text.literal(accepted
                                ? "ChatCraft: queued " + command : "ChatCraft: unknown command"));
                        return accepted ? 1 : 0;
                    })))
                .then(ClientCommandManager.literal("clearqueue").executes(context -> {
                    ChatCraftClient.queue().clear();
                    ChatCraftClient.input().releaseAll();
                    context.getSource().sendFeedback(Text.literal("ChatCraft: queue cleared and keys released"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("controls")
                    .then(ClientCommandManager.literal("on").executes(context -> {
                        setControls(true);
                        context.getSource().sendFeedback(Text.literal("ChatCraft: chat controls enabled"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        setControls(false);
                        ChatCraftClient.queue().clear();
                        ChatCraftClient.input().releaseAll();
                        context.getSource().sendFeedback(Text.literal(
                                "ChatCraft: chat controls disabled and queue cleared"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("overlay")
                    .then(ClientCommandManager.literal("on").executes(context -> {
                        setOverlay(true);
                        context.getSource().sendFeedback(Text.literal("ChatCraft: in-game overlay enabled"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        setOverlay(false);
                        context.getSource().sendFeedback(Text.literal("ChatCraft: in-game overlay disabled"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("event")
                    .then(ClientCommandManager.literal("follow").executes(context -> {
                        ChatCraftClient.eventSub().testFollow();
                        context.getSource().sendFeedback(Text.literal("ChatCraft: follow test queued"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("sub").executes(context -> {
                        ChatCraftClient.eventSub().testSub();
                        context.getSource().sendFeedback(Text.literal("ChatCraft: subscription test queued"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("nick")
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
                    })))
                .then(ClientCommandManager.literal("skin")
                    .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("username", StringArgumentType.word()).executes(context -> {
                            String username = StringArgumentType.getString(context, "username");
                            if (!ChatCraftClient.identity().setSkin(username)) {
                                context.getSource().sendError(Text.literal(
                                        "ChatCraft: Minecraft username must contain 1-16 letters, numbers, or underscores"));
                                return 0;
                            }
                            context.getSource().sendFeedback(Text.literal(
                                    "ChatCraft: loading skin from " + username + "..."));
                            return 1;
                        })))
                    .then(ClientCommandManager.literal("clear").executes(context -> {
                        ChatCraftClient.identity().clearSkin();
                        context.getSource().sendFeedback(Text.literal("ChatCraft: client-side skin cleared"));
                        return 1;
                    })))
        ));
    }

    private static int connect(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                               String platform) {
        if (!"youtube".equals(platform)) ChatCraftClient.twitch().connect();
        if (!"twitch".equals(platform)) ChatCraftClient.youtube().connect();
        source.sendFeedback(Text.literal("ChatCraft: " + platform + " connection requested"));
        return 1;
    }

    private static int disconnect(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                                  String platform) {
        if (!"youtube".equals(platform)) ChatCraftClient.twitch().disconnect();
        if (!"twitch".equals(platform)) ChatCraftClient.youtube().disconnect();
        source.sendFeedback(Text.literal("ChatCraft: " + platform + " disconnected"));
        return 1;
    }

    private static void setControls(boolean enabled) {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.controlsEnabled = enabled;
        config.save();
    }

    private static void setOverlay(boolean enabled) {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.overlayEnabled = enabled;
        config.save();
    }
}
