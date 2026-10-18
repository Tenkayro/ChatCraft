package de.tenkayro.chatcraft.dashboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public final class ActivityLog {
    private static final int MAX_EVENTS = 300;
    private static final int MAX_DIAGNOSTICS = 300;
    private static final int MAX_DIAGNOSTIC_LENGTH = 6_000;
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer|oauth)\\s+)[^\\s]+"
    );
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)((?:access|refresh|oauth)[_-]?token|client[_-]?secret)(\\s*[:=]\\s*)[^\\s,;&]+"
    );
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
    public synchronized void warn(String source, String message, Throwable cause) {
        diagnostic("warning", source, withException(message, cause));
    }
    public synchronized void error(String source, String message, Throwable cause) {
        diagnostic("error", source, withException(message, cause));
    }

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
                clean(source, 80), cleanDiagnostic(message)));
        while (diagnostics.size() > MAX_DIAGNOSTICS) diagnostics.removeFirst();
    }

    private static String withException(String message, Throwable cause) {
        StringBuilder detail = new StringBuilder(message == null ? "" : message);
        Throwable current = cause;
        int causeDepth = 0;
        while (current != null && causeDepth++ < 6) {
            if (!detail.isEmpty()) detail.append('\n');
            detail.append(causeDepth == 1 ? "Exception: " : "Caused by: ")
                    .append(current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                detail.append(": ").append(current.getMessage());
            }
            StackTraceElement[] trace = current.getStackTrace();
            for (int index = 0; index < Math.min(trace.length, 16); index++) {
                detail.append("\n  at ").append(trace[index]);
            }
            current = current.getCause();
        }
        return detail.toString();
    }

    private static String cleanDiagnostic(String value) {
        if (value == null) return "";
        String cleaned = value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        cleaned = AUTHORIZATION.matcher(cleaned).replaceAll("$1[REDACTED]");
        cleaned = SECRET_VALUE.matcher(cleaned).replaceAll("$1$2[REDACTED]");
        return cleaned.length() <= MAX_DIAGNOSTIC_LENGTH
                ? cleaned : cleaned.substring(0, MAX_DIAGNOSTIC_LENGTH) + "\n… truncated";
    }

    public record Event(long id, long timestamp, String category, String user, String message, String action,
                        String result) { }

    public record Diagnostic(long id, long timestamp, String level, String source, String message) { }
}
