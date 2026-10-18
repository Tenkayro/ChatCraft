package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ActionGroup;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.stream.Collectors;

public final class CommandService {
    private static final int MAX_COMMANDS_PER_WINDOW = 8;
    private static final long RATE_WINDOW_MILLIS = 10_000L;
    private static final int MAX_TOTAL_MOVEMENT_TICKS = 1_200;
    private final CommandParser parser;
    private final ActionQueue queue;
    private final ActivityLog activity;
    private final Map<String, Deque<Long>> recentCommands = new HashMap<>();

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
        if (!bypassPolicy && isRateLimited(normalizedUser)) {
            return rejected(normalizedUser, message, "rate limit: maximum 8 commands per 10 seconds");
        }
        List<ActionGroup> sequence = parser.parse(message);
        if (sequence.isEmpty()) return rejected(normalizedUser, message, "not a command");
        if (totalMovementTicks(sequence, config.movementDurationTicks) > MAX_TOTAL_MOVEMENT_TICKS) {
            return rejected(normalizedUser, message, "movement sequence exceeds the 60-second limit");
        }
        if (!queue.offerAll(sequence)) {
            return rejected(normalizedUser, message, "queue full: maximum 64 pending command groups");
        }
        if (!bypassPolicy) recordAcceptedCommand(normalizedUser);
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
        if (!queue.offer(new ChatAction(ChatAction.Type.MINECRAFT_COMMAND, resolved))) {
            return rejected(normalizedUser, message, "queue full: maximum 64 pending command groups");
        }
        activity.add("command", normalizedUser, message, "/" + resolved, "privileged command queued");
        return true;
    }

    public static boolean isMinecraftCommand(String message) {
        if (message == null) return false;
        String value = CommandParser.sanitizeMessage(message).stripLeading();
        return value.startsWith("?") && value.length() > 1;
    }

    private static String extractMinecraftCommand(String message) {
        String value = CommandParser.sanitizeMessage(message).stripLeading().substring(1).trim();
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

    private synchronized boolean isRateLimited(String user) {
        long cutoff = System.currentTimeMillis() - RATE_WINDOW_MILLIS;
        Deque<Long> timestamps = recentCommands.get(user);
        if (timestamps == null) return false;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) timestamps.removeFirst();
        if (timestamps.isEmpty()) recentCommands.remove(user);
        return timestamps.size() >= MAX_COMMANDS_PER_WINDOW;
    }

    private synchronized void recordAcceptedCommand(String user) {
        recentCommands.computeIfAbsent(user, ignored -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
        if (recentCommands.size() > 1_000) {
            long cutoff = System.currentTimeMillis() - RATE_WINDOW_MILLIS;
            recentCommands.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                    || entry.getValue().peekLast() < cutoff);
        }
    }

    private static int totalMovementTicks(List<ActionGroup> sequence, int defaultDuration) {
        int total = 0;
        for (ActionGroup group : sequence) {
            int longest = 0;
            for (ChatAction action : group.actions()) {
                if (!isMovement(action.type())) continue;
                longest = Math.max(longest, action.durationTicks() > 0 ? action.durationTicks() : defaultDuration);
            }
            total += longest;
        }
        return total;
    }

    private static boolean isMovement(ChatAction.Type type) {
        return switch (type) {
            case FORWARD, BACK, LEFT, RIGHT, JUMP, SPRINT -> true;
            default -> false;
        };
    }

    private static String describe(ChatAction action) {
        String description = action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
        return action.durationTicks() > 0 ? description + " (" + action.durationTicks() + " ticks)" : description;
    }

    private static String describeGroup(ActionGroup group) {
        return group.actions().stream().map(CommandService::describe).collect(Collectors.joining(" + "));
    }
}
