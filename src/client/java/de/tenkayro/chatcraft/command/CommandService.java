package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.config.ChatCraftConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CommandService {
    private final CommandParser parser;
    private final ActionQueue queue;
    private final Map<String, Long> lastAccepted = new ConcurrentHashMap<>();

    public CommandService(CommandParser parser, ActionQueue queue) {
        this.parser = parser;
        this.queue = queue;
    }

    public boolean submit(String user, String message, boolean bypassPolicy) {
        ChatCraftConfig config = ChatCraftConfig.get();
        if (!config.controlsEnabled && !bypassPolicy) return false;
        String normalizedUser = user == null ? "unknown" : user.toLowerCase();
        if (!bypassPolicy && !config.userAllowed(normalizedUser)) return false;
        long now = System.currentTimeMillis();
        long prior = lastAccepted.getOrDefault(normalizedUser, 0L);
        if (!bypassPolicy && now - prior < config.cooldownMillis) return false;
        return parser.parse(message).map(action -> {
            boolean accepted = queue.offer(action);
            if (accepted) lastAccepted.put(normalizedUser, now);
            return accepted;
        }).orElse(false);
    }
}
