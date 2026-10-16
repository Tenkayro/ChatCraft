package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ActionGroup;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;

import java.util.Locale;
import java.util.List;
import java.util.stream.Collectors;

public final class CommandService {
    private final CommandParser parser;
    private final ActionQueue queue;
    private final ActivityLog activity;

    public CommandService(CommandParser parser, ActionQueue queue, ActivityLog activity) {
        this.parser = parser;
        this.queue = queue;
        this.activity = activity;
    }

    public boolean submit(String user, String message, boolean bypassPolicy) {
        ChatCraftConfig config = ChatCraftConfig.get();
        String normalizedUser = user == null ? "unknown" : user.toLowerCase(Locale.ROOT);
        if (!config.controlsEnabled && !bypassPolicy) return rejected(normalizedUser, message, "controls disabled");
        if (!bypassPolicy && !config.userAllowed(normalizedUser)) return rejected(normalizedUser, message, "user blocked");
        List<ActionGroup> sequence = parser.parse(message);
        if (sequence.isEmpty()) return rejected(normalizedUser, message, "not a command");
        queue.offerAll(sequence);
        activity.add("command", normalizedUser, message,
                sequence.stream().map(CommandService::describeGroup).collect(Collectors.joining(" -> ")), "queued");
        return true;
    }

    public boolean submitMinecraftCommand(String user, String message, boolean privileged) {
        ChatCraftConfig config = ChatCraftConfig.get();
        String normalizedUser = user == null ? "unknown" : user.toLowerCase(Locale.ROOT);
        if (!config.controlsEnabled) return rejected(normalizedUser, message, "controls disabled");
        if (!config.privilegedCommandsEnabled) {
            return rejected(normalizedUser, message, "moderator commands disabled");
        }
        if (!privileged) return rejected(normalizedUser, message, "broadcaster/moderator only");
        String command = extractMinecraftCommand(message);
        if (command.isEmpty()) return rejected(normalizedUser, message, "missing Minecraft command");
        if (command.length() > 256 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return rejected(normalizedUser, message, "invalid Minecraft command");
        }
        if (command.equalsIgnoreCase("restart")) {
            queue.offerPriority(new ChatAction(ChatAction.Type.ADMIN_RESTART_WORLD));
            activity.add("command", normalizedUser, message, "restart world", "privileged restart queued");
            return true;
        }
        String resolved = resolveAdminAlias(command);
        queue.offer(new ChatAction(ChatAction.Type.MINECRAFT_COMMAND, resolved));
        activity.add("command", normalizedUser, message, "/" + resolved, "privileged command queued");
        return true;
    }

    public static boolean isMinecraftCommand(String message) {
        if (message == null) return false;
        String value = CommandParser.sanitizeMessage(message).stripLeading();
        return value.regionMatches(true, 0, ".cmd", 0, 4)
                && value.length() > 4 && Character.isWhitespace(value.charAt(4));
    }

    private static String extractMinecraftCommand(String message) {
        String value = CommandParser.sanitizeMessage(message).stripLeading().substring(4).trim();
        if (value.startsWith("/")) value = value.substring(1).stripLeading();
        return value;
    }

    private static String resolveAdminAlias(String command) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "creative" -> "gamemode creative";
            case "survival" -> "gamemode survival";
            case "day" -> "time set day";
            case "night" -> "time set night";
            case "clearweather" -> "weather clear";
            case "keepinventory" -> "gamerule keepInventory true";
            case "instantrespawn" -> "gamerule doImmediateRespawn true";
            case "heal" -> "effect give @s minecraft:instant_health 1 10 true";
            case "feed" -> "effect give @s minecraft:saturation 1 10 true";
            default -> command;
        };
    }

    private boolean rejected(String user, String message, String reason) {
        activity.add("command", user, message, "", reason);
        return false;
    }

    private static String describe(ChatAction action) {
        String description = action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
        return action.durationTicks() > 0 ? description + " (" + action.durationTicks() + " ticks)" : description;
    }

    private static String describeGroup(ActionGroup group) {
        return group.actions().stream().map(CommandService::describe).collect(Collectors.joining(" + "));
    }
}
