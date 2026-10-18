package de.tenkayro.chatcraft.dashboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class ActivityLog {
    private static final int MAX_EVENTS = 300;
    private static final int MAX_DIAGNOSTICS = 300;
    private final Deque<Event> events = new ArrayDeque<>();
    private final Deque<Diagnostic> diagnostics = new ArrayDeque<>();
    private long nextId;
    private long nextDiagnosticId;

    public synchronized void add(String category, String user, String message, String action, String result) {
        events.addLast(new Event(++nextId, System.currentTimeMillis(), clean(category, 24), clean(user, 64),
            clean(message, 512), clean(action, 80), clean(result, 180)));
        while (events.size() > MAX_EVENTS) events.removeFirst();
        mirrorDiagnostic(category, action, result);
    }

    public synchronized void info(String source, String message) { diagnostic("info", source, message); }
    public synchronized void warn(String source, String message) { diagnostic("warning", source, message); }
    public synchronized void error(String source, String message) { diagnostic("error", source, message); }

    public synchronized List<Diagnostic> diagnosticSnapshot() {
        List<Diagnostic> copy = new ArrayList<>(diagnostics.size());
        Iterator<Diagnostic> iterator = diagnostics.descendingIterator();
        while (iterator.hasNext()) copy.add(iterator.next());
        return copy;
    }

    public synchronized void clearDiagnostics() {
        diagnostics.clear();
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

    private void mirrorDiagnostic(String category, String action, String result) {
        String detail = result == null ? "" : result;
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        String source = action == null || action.isBlank() ? category : action;
        if (normalized.contains("error") || normalized.contains("failed")
                || normalized.contains("connection lost") || normalized.contains("could not")) {
            diagnostic("error", source, detail);
        } else if (normalized.contains("blocked") || normalized.contains("invalid")
                || normalized.contains("disabled") || normalized.contains("required")
                || normalized.contains("missing") || normalized.contains("rejected")
                || normalized.contains("expired")) {
            diagnostic("warning", source, detail);
        } else if ("system".equals(category) || "admin".equals(category)) {
            diagnostic("info", source, detail);
        }
    }

    private void diagnostic(String level, String source, String message) {
        diagnostics.addLast(new Diagnostic(++nextDiagnosticId, System.currentTimeMillis(), clean(level, 12),
                clean(source, 80), clean(message, 600)));
        while (diagnostics.size() > MAX_DIAGNOSTICS) diagnostics.removeFirst();
    }

    public record Event(long id, long timestamp, String category, String user, String message, String action,
                        String result) { }

    public record Diagnostic(long id, long timestamp, String level, String source, String message) { }
}
