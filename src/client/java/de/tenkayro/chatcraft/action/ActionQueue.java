package de.tenkayro.chatcraft.action;

import de.tenkayro.chatcraft.config.ChatCraftConfig;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ActionQueue {
    private final Deque<ChatAction> actions = new ArrayDeque<>();

    public synchronized boolean offer(ChatAction action) {
        int capacity = ChatCraftConfig.get().queueSize;
        if (actions.size() >= capacity) return false;
        actions.addLast(action);
        return true;
    }

    public synchronized void offerPriority(ChatAction action) {
        actions.clear();
        actions.addFirst(action);
    }

    public synchronized ChatAction poll() {
        return actions.pollFirst();
    }

    public synchronized void clear() {
        actions.clear();
    }

    public synchronized int size() {
        return actions.size();
    }
}
