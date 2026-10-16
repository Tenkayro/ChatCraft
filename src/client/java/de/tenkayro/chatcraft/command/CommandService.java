package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ActionQueue;
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
        List<ChatAction> actions = parser.parse(message);
        if (actions.isEmpty()) return rejected(normalizedUser, message, "not a command");
        queue.offerAll(actions);
        activity.add("command", normalizedUser, message,
                actions.stream().map(CommandService::describe).collect(Collectors.joining(" -> ")), "queued");
        return true;
    }

    private boolean rejected(String user, String message, String reason) {
        activity.add("command", user, message, "", reason);
        return false;
    }

    private static String describe(ChatAction action) {
        String description = action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
        return action.durationTicks() > 0 ? description + " (" + action.durationTicks() + " ticks)" : description;
    }
}
