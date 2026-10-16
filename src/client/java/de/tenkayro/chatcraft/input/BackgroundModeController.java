package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class BackgroundModeController {
    private static volatile boolean chatOnly;
    private final InputController input;
    private final KeyBinding toggleChatControl = KeyBindingHelper.registerKeyBinding(new KeyBinding(
        "key.chatcraft.toggle_chat_control",
        InputUtil.Type.KEYSYM,
        GLFW.GLFW_KEY_F8,
        "category.chatcraft"
    ));
    private Boolean appliedKeepRunning;

    public BackgroundModeController(InputController input) {
        this.input = input;
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

        while (toggleChatControl.wasPressed()) {
            chatOnly = !chatOnly;
            input.releaseAll();
            KeyBinding.unpressAll();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable(chatOnly
                        ? "message.chatcraft.chat_control_on"
                        : "message.chatcraft.chat_control_off"), true);
            }
        }
    }

    public static boolean isChatOnly() {
        return chatOnly;
    }
}
