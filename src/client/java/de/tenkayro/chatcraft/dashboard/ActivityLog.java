package de.tenkayro.chatcraft.dashboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class ActivityLog {
    private static final int MAX_EVENTS = 300;
    private final Deque<Event> events = new ArrayDeque<>();
    private long nextId;

    public synchronized void add(String category, String user, String message, String action, String result) {
        events.addLast(new Event(++nextId, System.currentTimeMillis(), clean(category, 24), clean(user, 64),
            clean(message, 512), clean(action, 80), clean(result, 180)));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    public synchronized List<Event> snapshot() {
        List<Event> copy = new ArrayList<>(events.size());
        Iterator<Event> iterator = events.descendingIterator();
        while (iterator.hasNext()) copy.add(iterator.next());
        return copy;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "…";
    }

    public record Event(long id, long timestamp, String category, String user, String message, String action,
                        String result) { }
}
