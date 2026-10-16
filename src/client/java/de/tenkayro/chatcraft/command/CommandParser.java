package de.tenkayro.chatcraft.command;

import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandParser {
    private static final Pattern SLOT = Pattern.compile("^(R|S)?([A-Z]+[1-9][0-9]*)$");

    public Optional<ChatAction> parse(String raw) {
        if (raw == null) return Optional.empty();
        String value = raw.trim();
        String prefix = ChatCraftConfig.get().commandPrefix;
        if (!prefix.isEmpty()) {
            if (!value.startsWith(prefix)) return Optional.empty();
            value = value.substring(prefix.length()).trim();
        }
        String command = value.toUpperCase(Locale.ROOT);
        Matcher slot = SLOT.matcher(command);
        if (slot.matches()) {
            ChatAction.Type type = switch (slot.group(1) == null ? "" : slot.group(1)) {
                case "R" -> ChatAction.Type.SLOT_RIGHT;
                case "S" -> ChatAction.Type.SLOT_SHIFT;
                default -> ChatAction.Type.SLOT_LEFT;
            };
            return Optional.of(new ChatAction(type, slot.group(2)));
        }
        if (command.matches("[1-9]")) return Optional.of(new ChatAction(ChatAction.Type.HOTBAR, command));
        return Optional.ofNullable(switch (command) {
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
            case "RESPAWN" -> new ChatAction(ChatAction.Type.RESPAWN);
            default -> null;
        });
    }
}
