package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CommandService {
    private final CommandParser parser;
    private final ActionQueue queue;
    private final ActivityLog activity;
    private final Map<String, Long> lastAccepted = new ConcurrentHashMap<>();

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
        long now = System.currentTimeMillis();
        long prior = lastAccepted.getOrDefault(normalizedUser, 0L);
        if (!bypassPolicy && now - prior < config.cooldownMillis) return rejected(normalizedUser, message, "cooldown");
        Optional<ChatAction> parsed = parser.parse(message);
        if (parsed.isEmpty()) return rejected(normalizedUser, message, "not a command");
        ChatAction action = parsed.get();
        boolean accepted = queue.offer(action);
        if (accepted) lastAccepted.put(normalizedUser, now);
        activity.add("command", normalizedUser, message, describe(action), accepted ? "queued" : "queue full");
        return accepted;
    }

    private boolean rejected(String user, String message, String reason) {
        activity.add("command", user, message, "", reason);
        return false;
    }

    private static String describe(ChatAction action) {
        return action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
    }
}
