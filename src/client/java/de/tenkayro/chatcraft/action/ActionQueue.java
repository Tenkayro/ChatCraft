package de.tenkayro.chatcraft.action;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class ActionQueue {
    public static final int MAX_QUEUED_GROUPS = 64;
    private final Deque<ActionGroup> actions = new ArrayDeque<>();

    public synchronized boolean offer(ChatAction action) {
        if (actions.size() >= MAX_QUEUED_GROUPS) return false;
        actions.addLast(new ActionGroup(action));
        return true;
    }

    public synchronized boolean offerAll(List<ActionGroup> sequence) {
        if (sequence.isEmpty()) return true;
        if (sequence.size() > MAX_QUEUED_GROUPS - actions.size()) return false;
        actions.addAll(sequence);
        return true;
    }

    public synchronized void offerPriority(ChatAction action) {
        actions.clear();
        actions.addFirst(new ActionGroup(action));
    }

    public synchronized ActionGroup poll() {
        return actions.pollFirst();
    }

    public synchronized void clear() {
        actions.clear();
    }

    public synchronized int size() {
        return actions.size();
    }
}
