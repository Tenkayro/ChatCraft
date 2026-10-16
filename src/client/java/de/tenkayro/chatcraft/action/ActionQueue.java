package de.tenkayro.chatcraft.action;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class ActionQueue {
    private final Deque<ActionGroup> actions = new ArrayDeque<>();

    public synchronized boolean offer(ChatAction action) {
        actions.addLast(new ActionGroup(action));
        return true;
    }

    public synchronized void offerAll(List<ActionGroup> sequence) {
        actions.addAll(sequence);
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
