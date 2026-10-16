package de.tenkayro.chatcraft.action;

public record ChatAction(Type type, String argument) {
    public ChatAction(Type type) {
        this(type, "");
    }

    public enum Type {
        FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT,
        INVENTORY, CLOSE_SCREEN, DROP, HOTBAR, ATTACK, USE, BREAK, PLACE,
        CAMERA_LEFT, CAMERA_RIGHT, CAMERA_UP, CAMERA_DOWN,
        RESPAWN, SLOT_LEFT, SLOT_RIGHT, SLOT_SHIFT, RELEASE_ALL
    }
}
