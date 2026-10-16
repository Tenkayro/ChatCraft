package de.tenkayro.chatcraft.action;

public record ChatAction(Type type, String argument, int durationTicks) {
    public ChatAction(Type type) {
        this(type, "", 0);
    }

    public ChatAction(Type type, String argument) {
        this(type, argument, 0);
    }

    public enum Type {
        FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT,
        INVENTORY, CLOSE_SCREEN, DROP, HOTBAR, ATTACK, USE, BREAK, PLACE,
        RECIPE_BOOK, RECIPE_SELECT, RECIPE_NEXT, RECIPE_PREVIOUS,
        CAMERA_LEFT, CAMERA_RIGHT, CAMERA_UP, CAMERA_DOWN, CAMERA_AROUND,
        RESPAWN, SLOT_LEFT, SLOT_RIGHT, SLOT_SHIFT,
        FOLLOW_EVENT, SUB_EVENT, RELEASE_ALL
    }
}
