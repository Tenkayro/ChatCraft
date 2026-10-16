package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

public final class BackgroundModeController {
    private static volatile boolean chatOnly;
    private static BackgroundModeController instance;
    private final InputController input;
    private Boolean appliedKeepRunning;

    public BackgroundModeController(InputController input) {
        this.input = input;
        instance = this;
    }

    public void tick(MinecraftClient client) {
        boolean keepRunning = ChatCraftConfig.get().keepRunningUnfocused;
        if (appliedKeepRunning == null || appliedKeepRunning != keepRunning) {
            boolean pauseOnLostFocus = !keepRunning;
            if (client.options.pauseOnLostFocus != pauseOnLostFocus) {
                client.options.pauseOnLostFocus = pauseOnLostFocus;
                client.options.write();
            }
            appliedKeepRunning = keepRunning;
        }
    }

    /** Called directly from the raw F8 event so the unlock key can never block itself. */
    public static void toggle(MinecraftClient client) {
        BackgroundModeController controller = instance;
        if (controller == null) return;
        chatOnly = !chatOnly;
        controller.input.releaseAll();
        KeyBinding.unpressAll();
        if (client.player != null) {
            client.player.sendMessage(Text.translatable(chatOnly
                    ? "message.chatcraft.chat_control_on"
                    : "message.chatcraft.chat_control_off"), true);
        }
    }

    public static boolean isChatOnly() {
        return chatOnly;
    }
}
