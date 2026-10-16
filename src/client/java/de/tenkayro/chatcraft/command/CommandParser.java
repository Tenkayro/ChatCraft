package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandParser {
    private static final Pattern SLOT = Pattern.compile("^(R|S)?([A-Z]+[1-9][0-9]*)$");
    private static final Pattern TIMED = Pattern.compile("^([A-Z]+)\\.([0-9]+(?:[.,][0-9]+)?)$");
    private static final Pattern INVISIBLE_DUPLICATE_MARKERS = Pattern.compile("[\\u034F\\u200B\\u2060\\uFEFF]");

    public List<ChatAction> parse(String raw) {
        if (raw == null) return List.of();
        String value = sanitizeMessage(raw).trim();
        String prefix = ChatCraftConfig.get().commandPrefix;
        if (!prefix.isEmpty()) {
            if (!value.startsWith(prefix)) return List.of();
            value = value.substring(prefix.length()).trim();
        }
        if (value.isEmpty()) return List.of();
        List<ChatAction> sequence = new ArrayList<>();
        for (String part : value.split("\\s*\\+\\s*", -1)) {
            ChatAction action = parseSingle(part);
            if (action == null) return List.of();
            sequence.add(action);
        }
        return List.copyOf(sequence);
    }

    public static String sanitizeMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        return INVISIBLE_DUPLICATE_MARKERS.matcher(message).replaceAll("");
    }

    private ChatAction parseSingle(String raw) {
        String command = raw.trim().toUpperCase(Locale.ROOT);
        Matcher timed = TIMED.matcher(command);
        if (timed.matches()) {
            ChatAction action = parseCommand(timed.group(1));
            if (action == null || !supportsDuration(action.type())) return null;
            try {
                double seconds = Double.parseDouble(timed.group(2).replace(',', '.'));
                if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > 30.0) return null;
                return new ChatAction(action.type(), action.argument(), Math.max(1, (int) Math.round(seconds * 20.0)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return parseCommand(command);
    }

    private ChatAction parseCommand(String command) {
        Matcher slot = SLOT.matcher(command);
        if (slot.matches()) {
            ChatAction.Type type = switch (slot.group(1) == null ? "" : slot.group(1)) {
                case "R" -> ChatAction.Type.SLOT_RIGHT;
                case "S" -> ChatAction.Type.SLOT_SHIFT;
                default -> ChatAction.Type.SLOT_LEFT;
            };
            return new ChatAction(type, slot.group(2));
        }
        if (command.matches("[1-9]")) return new ChatAction(ChatAction.Type.HOTBAR, command);
        return switch (command) {
            case "W", "FORWARD" -> new ChatAction(ChatAction.Type.FORWARD);
            case "S", "BACK", "BACKWARD" -> new ChatAction(ChatAction.Type.BACK);
            case "A", "STRAFELEFT" -> new ChatAction(ChatAction.Type.LEFT);
            case "D", "STRAFERIGHT" -> new ChatAction(ChatAction.Type.RIGHT);
            case "JUMP", "SPACE" -> new ChatAction(ChatAction.Type.JUMP);
            case "SNEAK", "CROUCH" -> new ChatAction(ChatAction.Type.SNEAK);
            case "SPRINT" -> new ChatAction(ChatAction.Type.SPRINT);
            case "E", "INVENTORY", "INV" -> new ChatAction(ChatAction.Type.INVENTORY);
            case "ESC", "CLOSE" -> new ChatAction(ChatAction.Type.CLOSE_SCREEN);
            case "Q", "DROP" -> new ChatAction(ChatAction.Type.DROP);
            case "ATTACK", "HIT" -> new ChatAction(ChatAction.Type.ATTACK);
            case "USE", "INTERACT" -> new ChatAction(ChatAction.Type.USE);
            case "BREAK", "MINE" -> new ChatAction(ChatAction.Type.BREAK);
            case "PLACE", "BUILD" -> new ChatAction(ChatAction.Type.PLACE);
            case "LEFT", "CAMLEFT" -> new ChatAction(ChatAction.Type.CAMERA_LEFT);
            case "RIGHT", "CAMRIGHT" -> new ChatAction(ChatAction.Type.CAMERA_RIGHT);
            case "UP", "CAMUP" -> new ChatAction(ChatAction.Type.CAMERA_UP);
            case "DOWN", "CAMDOWN" -> new ChatAction(ChatAction.Type.CAMERA_DOWN);
            case "180", "AROUND", "TURNAROUND", "UTURN" -> new ChatAction(ChatAction.Type.CAMERA_AROUND);
            case "RESPAWN" -> new ChatAction(ChatAction.Type.RESPAWN);
            default -> null;
        };
    }

    private static boolean supportsDuration(ChatAction.Type type) {
        return switch (type) {
            case FORWARD, BACK, LEFT, RIGHT, JUMP, SPRINT -> true;
            default -> false;
        };
    }
}
