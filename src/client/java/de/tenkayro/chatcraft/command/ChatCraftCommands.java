package de.tenkayro.chatcraft.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.identity.ClientIdentity;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatCraftCommands {
    private static long clientTick;
    private static long twitchFeedbackDeadline;
    private static long youtubeFeedbackDeadline;

    private ChatCraftCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("cc")
                .executes(context -> showHelp(context.getSource()))
                .then(ClientCommandManager.literal("help")
                    .executes(context -> showHelp(context.getSource())))
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
                .then(ClientCommandManager.literal("status")
                    .executes(context -> showStatus(context.getSource())))
                .then(ClientCommandManager.literal("stats")
                    .executes(context -> showStats(context.getSource()))
                    .then(statsToggle("deaths"))
                    .then(statsToggle("playtime"))
                    .then(statsToggle("mobkills"))
                    .then(statsToggle("playerkills"))
                    .then(statsToggle("distance"))
                    .then(statsToggle("jumps"))
                    .then(ClientCommandManager.literal("twitch")
                        .then(platformStatsToggle("twitch", "followers"))
                        .then(platformStatsToggle("twitch", "viewers")))
                    .then(ClientCommandManager.literal("youtube")
                        .then(platformStatsToggle("youtube", "subscribers"))
                        .then(platformStatsToggle("youtube", "followers"))
                        .then(platformStatsToggle("youtube", "viewers")))
                    .then(ClientCommandManager.literal("title")
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(context -> setStatsTitle(context.getSource(),
                                        StringArgumentType.getString(context, "text")))))
                        .then(ClientCommandManager.literal("show")
                            .executes(context -> setStatsTitleVisible(context.getSource(), true)))
                        .then(ClientCommandManager.literal("on")
                            .executes(context -> setStatsTitleVisible(context.getSource(), true)))
                        .then(ClientCommandManager.literal("hide")
                            .executes(context -> setStatsTitleVisible(context.getSource(), false)))
                        .then(ClientCommandManager.literal("off")
                            .executes(context -> setStatsTitleVisible(context.getSource(), false)))
                        .then(ClientCommandManager.literal("reset")
                            .executes(context -> resetStatsTitle(context.getSource()))))
                    .then(statsToggle("all")))
                .then(ClientCommandManager.literal("dashboard").executes(context -> {
                    context.getSource().sendFeedback(ChatCraftClient.dashboardMessage());
                    return ChatCraftClient.dashboard().running() ? 1 : 0;
                }))
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("command", StringArgumentType.greedyString()).executes(context -> {
                        String command = StringArgumentType.getString(context, "command");
                        boolean accepted = ChatCraftClient.commands().submit("local-test", command, true);
                        if (accepted) success(context.getSource(), "Queued test command: " + command);
                        else error(context.getSource(), "Unknown or invalid test command: " + command);
                        return accepted ? 1 : 0;
                    })))
                .then(ClientCommandManager.literal("queue")
                    .then(ClientCommandManager.literal("clear").executes(context -> {
                        ChatCraftClient.queue().clear();
                        ChatCraftClient.input().releaseAll();
                        success(context.getSource(), "Queue cleared and all simulated keys released");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("controls")
                    .then(ClientCommandManager.literal("on").executes(context -> {
                        setControls(true);
                        success(context.getSource(), "Chat controls enabled");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        setControls(false);
                        ChatCraftClient.queue().clear();
                        ChatCraftClient.input().releaseAll();
                        success(context.getSource(), "Chat controls disabled; queue cleared");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("overlay")
                    .then(ClientCommandManager.literal("on").executes(context -> {
                        setOverlay(true);
                        success(context.getSource(), "In-game overlay enabled");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        setOverlay(false);
                        success(context.getSource(), "In-game overlay disabled");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("event")
                    .then(ClientCommandManager.literal("follow").executes(context -> {
                        ChatCraftClient.eventSub().testFollow();
                        success(context.getSource(), "Follow test queued");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("sub").executes(context -> {
                        ChatCraftClient.eventSub().testSub();
                        success(context.getSource(), "Subscription test queued");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("nick")
                    .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            if (!ClientIdentity.isValidName(name)) {
                                error(context.getSource(),
                                        "Nickname must contain 1-16 letters, numbers, or underscores");
                                return 0;
                            }
                            ChatCraftClient.identity().setNickname(name);
                            success(context.getSource(), "Nickname set to " + name);
                            return 1;
                        })))
                    .then(ClientCommandManager.literal("clear").executes(context -> {
                        ChatCraftClient.identity().clearNickname();
                        success(context.getSource(), "Nickname reset to the account name");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("skin")
                    .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("username", StringArgumentType.word()).executes(context -> {
                            String username = StringArgumentType.getString(context, "username");
                            if (!ChatCraftClient.identity().setSkin(username)) {
                                error(context.getSource(),
                                        "Minecraft username must contain 1-16 letters, numbers, or underscores");
                                return 0;
                            }
                            info(context.getSource(), "Loading skin from " + username + "...");
                            return 1;
                        })))
                    .then(ClientCommandManager.literal("clear").executes(context -> {
                        ChatCraftClient.identity().clearSkin();
                        success(context.getSource(), "Skin reset to the account skin");
                        return 1;
                    })))
        ));
    }

    private static int connect(FabricClientCommandSource source, String platform) {
        if (!"youtube".equals(platform)) {
            if (ChatCraftClient.twitch().state() == de.tenkayro.chatcraft.twitch.TwitchConnection.State.CONNECTED) {
                info(source, "Twitch is already connected — " + ChatCraftClient.twitch().detail());
            } else {
                ChatCraftClient.twitch().connect();
                info(source, "Twitch: connecting...");
                twitchFeedbackDeadline = clientTick + 400;
            }
        }
        if (!"twitch".equals(platform)) {
            ChatCraftClient.youtube().connect();
            switch (ChatCraftClient.youtube().state()) {
                case AUTH_REQUIRED -> error(source, "YouTube: " + ChatCraftClient.youtube().detail());
                case CONNECTED -> info(source, "YouTube is already connected — " + ChatCraftClient.youtube().detail());
                default -> {
                    info(source, "YouTube: looking for a live broadcast...");
                    youtubeFeedbackDeadline = clientTick + 400;
                }
            }
        }
        return 1;
    }

    private static int disconnect(FabricClientCommandSource source, String platform) {
        if (!"youtube".equals(platform)) {
            ChatCraftClient.twitch().disconnect();
            twitchFeedbackDeadline = 0;
            success(source, "Twitch disconnected");
        }
        if (!"twitch".equals(platform)) {
            ChatCraftClient.youtube().disconnect();
            youtubeFeedbackDeadline = 0;
            success(source, "YouTube disconnected");
        }
        return 1;
    }

    public static void tick(MinecraftClient client) {
        clientTick++;
        if (client.player == null) return;

        if (twitchFeedbackDeadline > 0) {
            switch (ChatCraftClient.twitch().state()) {
                case CONNECTED -> {
                    notifyPlayer(client, "Twitch connected — " + ChatCraftClient.twitch().detail(), Formatting.GREEN);
                    twitchFeedbackDeadline = 0;
                }
                case ERROR, DISCONNECTED -> {
                    notifyPlayer(client, "Twitch: " + ChatCraftClient.twitch().detail(), Formatting.RED);
                    twitchFeedbackDeadline = 0;
                }
                case CONNECTING -> {
                    if (clientTick >= twitchFeedbackDeadline) {
                        notifyPlayer(client, "Twitch is still connecting — check /cc status", Formatting.YELLOW);
                        twitchFeedbackDeadline = 0;
                    }
                }
            }
        }

        if (youtubeFeedbackDeadline > 0) {
            switch (ChatCraftClient.youtube().state()) {
                case CONNECTED -> {
                    notifyPlayer(client, ChatCraftClient.youtube().detail(), Formatting.GREEN);
                    youtubeFeedbackDeadline = 0;
                }
                case WAITING -> {
                    notifyPlayer(client, "YouTube: " + ChatCraftClient.youtube().detail(), Formatting.YELLOW);
                    youtubeFeedbackDeadline = 0;
                }
                case ERROR, AUTH_REQUIRED, DISCONNECTED -> {
                    notifyPlayer(client, "YouTube: " + ChatCraftClient.youtube().detail(), Formatting.RED);
                    youtubeFeedbackDeadline = 0;
                }
                case CONNECTING -> {
                    if (clientTick >= youtubeFeedbackDeadline) {
                        notifyPlayer(client, "YouTube is still connecting — check /cc status", Formatting.YELLOW);
                        youtubeFeedbackDeadline = 0;
                    }
                }
            }
        }
    }

    private static int showHelp(FabricClientCommandSource source) {
        MutableText help = prefix().append(Text.literal("Quick commands\n").formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal("/cc connect [twitch|youtube]").formatted(Formatting.AQUA))
                .append(Text.literal("  Connect chat\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc disconnect [twitch|youtube]").formatted(Formatting.AQUA))
                .append(Text.literal("  Disconnect chat\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc status").formatted(Formatting.AQUA))
                .append(Text.literal("  Show live status\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc controls on|off").formatted(Formatting.AQUA))
                .append(Text.literal("  Toggle chat control\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc overlay on|off").formatted(Formatting.AQUA))
                .append(Text.literal("  Toggle labels\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc stats").formatted(Formatting.AQUA))
                .append(Text.literal("  Configure world stats\n").formatted(Formatting.GRAY))
                .append(Text.literal("/cc nick …  /cc skin …  /cc queue clear").formatted(Formatting.DARK_AQUA));
        source.sendFeedback(help);
        return 1;
    }

    private static int showStatus(FabricClientCommandSource source) {
        ChatCraftConfig config = ChatCraftConfig.get();
        MutableText status = prefix().append(Text.literal("Live status\n").formatted(Formatting.WHITE, Formatting.BOLD))
                .append(statusLine("Twitch", ChatCraftClient.twitch().detail(),
                        ChatCraftClient.twitch().state() == de.tenkayro.chatcraft.twitch.TwitchConnection.State.CONNECTED))
                .append(statusLine("YouTube", ChatCraftClient.youtube().detail(),
                        ChatCraftClient.youtube().state() == de.tenkayro.chatcraft.youtube.YouTubeConnection.State.CONNECTED))
                .append(Text.literal("Controls: ").formatted(Formatting.GRAY))
                .append(Text.literal(config.controlsEnabled ? "ON" : "OFF")
                        .formatted(config.controlsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("  •  Overlay: ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(config.overlayEnabled ? "ON" : "OFF")
                        .formatted(config.overlayEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("  •  Queue: " + ChatCraftClient.queue().size()).formatted(Formatting.GRAY));
        source.sendFeedback(status);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> statsToggle(
            String stat) {
        return ClientCommandManager.literal(stat)
                .then(ClientCommandManager.argument("visible", BoolArgumentType.bool())
                        .executes(context -> setStatVisible(context.getSource(), stat,
                                BoolArgumentType.getBool(context, "visible"))));
    }

    private static int setStatVisible(FabricClientCommandSource source, String stat, boolean visible) {
        ChatCraftConfig config = ChatCraftConfig.get();
        switch (stat) {
            case "deaths" -> config.statsDeaths = visible;
            case "playtime" -> config.statsPlaytime = visible;
            case "mobkills" -> config.statsMobKills = visible;
            case "playerkills" -> config.statsPlayerKills = visible;
            case "distance" -> config.statsDistance = visible;
            case "jumps" -> config.statsJumps = visible;
            case "all" -> {
                config.statsDeaths = visible;
                config.statsPlaytime = visible;
                config.statsMobKills = visible;
                config.statsPlayerKills = visible;
                config.statsDistance = visible;
                config.statsJumps = visible;
                config.statsTwitchFollowers = visible;
                config.statsTwitchViewers = visible;
                config.statsYoutubeSubscribers = visible;
                config.statsYoutubeViewers = visible;
            }
            default -> {
                error(source, "Unknown statistic: " + stat);
                return 0;
            }
        }
        config.save();
        ChatCraftClient.platformStats().requestRefresh();
        success(source, ("all".equals(stat) ? "Stats" : statLabel(stat))
                + (visible ? " shown" : " hidden"));
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> platformStatsToggle(
            String platform, String metric) {
        return ClientCommandManager.literal(metric)
                .then(ClientCommandManager.argument("visible", BoolArgumentType.bool())
                        .executes(context -> setPlatformStatVisible(context.getSource(), platform, metric,
                                BoolArgumentType.getBool(context, "visible"))));
    }

    private static int setPlatformStatVisible(FabricClientCommandSource source, String platform,
                                              String metric, boolean visible) {
        ChatCraftConfig config = ChatCraftConfig.get();
        if ("twitch".equals(platform) && "followers".equals(metric)) config.statsTwitchFollowers = visible;
        else if ("twitch".equals(platform) && "viewers".equals(metric)) config.statsTwitchViewers = visible;
        else if ("youtube".equals(platform) && ("subscribers".equals(metric) || "followers".equals(metric))) {
            config.statsYoutubeSubscribers = visible;
        } else if ("youtube".equals(platform) && "viewers".equals(metric)) config.statsYoutubeViewers = visible;
        else {
            error(source, "Unknown platform statistic");
            return 0;
        }
        config.save();
        ChatCraftClient.platformStats().requestRefresh();
        String label = "youtube".equals(platform) && "followers".equals(metric) ? "subscribers" : metric;
        success(source, statLabel(platform) + " " + statLabel(label) + (visible ? " shown" : " hidden"));
        return 1;
    }

    private static int showStats(FabricClientCommandSource source) {
        ChatCraftConfig config = ChatCraftConfig.get();
        MutableText status = prefix().append(Text.literal("Stats overlay\n").formatted(Formatting.WHITE, Formatting.BOLD))
                .append(toggleLine("Deaths", config.statsDeaths))
                .append(toggleLine("Playtime", config.statsPlaytime))
                .append(toggleLine("Mob kills", config.statsMobKills))
                .append(toggleLine("Player kills", config.statsPlayerKills))
                .append(toggleLine("Distance", config.statsDistance))
                .append(toggleLine("Jumps", config.statsJumps))
                .append(toggleLine("Twitch followers", config.statsTwitchFollowers))
                .append(toggleLine("Twitch viewers", config.statsTwitchViewers))
                .append(toggleLine("YouTube subscribers", config.statsYoutubeSubscribers))
                .append(toggleLine("YouTube viewers", config.statsYoutubeViewers))
                .append(Text.literal("Title: ").formatted(Formatting.GRAY))
                .append(Text.literal(config.statsTitleVisible ? config.statsTitle : "HIDDEN")
                        .formatted(config.statsTitleVisible ? Formatting.WHITE : Formatting.DARK_GRAY))
                .append(Text.literal("\n"))
                .append(Text.literal("/cc stats <stat|all> <true|false>\n").formatted(Formatting.DARK_AQUA))
                .append(Text.literal("/cc stats twitch|youtube <metric> <true|false>")
                        .formatted(Formatting.DARK_AQUA))
                .append(Text.literal("\n/cc stats title set|show|hide|reset").formatted(Formatting.DARK_AQUA));
        source.sendFeedback(status);
        return 1;
    }

    private static int setStatsTitle(FabricClientCommandSource source, String value) {
        String title = value.trim();
        if (title.isEmpty() || title.length() > 32) {
            error(source, "Stats title must contain 1-32 characters");
            return 0;
        }
        ChatCraftConfig config = ChatCraftConfig.get();
        config.statsTitle = title;
        config.statsTitleVisible = true;
        config.save();
        success(source, "Stats title set to " + title);
        return 1;
    }

    private static int setStatsTitleVisible(FabricClientCommandSource source, boolean visible) {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.statsTitleVisible = visible;
        config.save();
        success(source, "Stats title " + (visible ? "shown" : "hidden"));
        return 1;
    }

    private static int resetStatsTitle(FabricClientCommandSource source) {
        ChatCraftConfig config = ChatCraftConfig.get();
        config.statsTitle = "STATS";
        config.statsTitleVisible = true;
        config.save();
        success(source, "Stats title reset to STATS");
        return 1;
    }

    private static MutableText toggleLine(String label, boolean enabled) {
        return Text.literal(label + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(enabled ? "ON" : "OFF")
                        .formatted(enabled ? Formatting.GREEN : Formatting.DARK_GRAY))
                .append(Text.literal("\n"));
    }

    private static String statLabel(String stat) {
        return switch (stat) {
            case "playtime" -> "Playtime";
            case "mobkills" -> "Mob kills";
            case "playerkills" -> "Player kills";
            default -> Character.toUpperCase(stat.charAt(0)) + stat.substring(1);
        };
    }

    private static MutableText statusLine(String platform, String detail, boolean connected) {
        return Text.literal(platform + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(detail).formatted(connected ? Formatting.GREEN : Formatting.YELLOW))
                .append(Text.literal("\n"));
    }

    private static MutableText prefix() {
        return Text.literal("[ChatCraft] ").formatted(Formatting.GOLD);
    }

    private static void success(FabricClientCommandSource source, String message) {
        source.sendFeedback(prefix().append(Text.literal(message).formatted(Formatting.GREEN)));
    }

    private static void info(FabricClientCommandSource source, String message) {
        source.sendFeedback(prefix().append(Text.literal(message).formatted(Formatting.AQUA)));
    }

    private static void error(FabricClientCommandSource source, String message) {
        source.sendError(prefix().append(Text.literal(message).formatted(Formatting.RED)));
    }

    private static void notifyPlayer(MinecraftClient client, String message, Formatting color) {
        client.player.sendMessage(prefix().append(Text.literal(message).formatted(color)), false);
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
