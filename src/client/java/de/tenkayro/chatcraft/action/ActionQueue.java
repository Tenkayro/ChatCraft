package de.tenkayro.chatcraft.action;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class ActionQueue {
    private final Deque<ChatAction> actions = new ArrayDeque<>();

    public synchronized boolean offer(ChatAction action) {
        actions.addLast(action);
        return true;
    }

    public synchronized void offerAll(List<ChatAction> sequence) {
        actions.addAll(sequence);
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
