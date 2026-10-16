package de.tenkayro.chatcraft.action;

import java.util.List;

public record ActionGroup(List<ChatAction> actions) {
    public ActionGroup {
        actions = List.copyOf(actions);
        if (actions.isEmpty()) throw new IllegalArgumentException("An action group cannot be empty");
    }

    public ActionGroup(ChatAction action) {
        this(List.of(action));
    }
}
