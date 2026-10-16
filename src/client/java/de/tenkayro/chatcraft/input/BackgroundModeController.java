package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class BackgroundModeController {
    private final InputController input;
    private final KeyBinding releaseCursor = KeyBindingHelper.registerKeyBinding(new KeyBinding(
        "key.chatcraft.release_cursor",
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

        while (releaseCursor.wasPressed()) {
            if (client.currentScreen != null) continue;
            if (client.mouse.isCursorLocked()) {
                input.releaseAll();
                KeyBinding.unpressAll();
                client.mouse.unlockCursor();
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("message.chatcraft.cursor_released"), true);
                }
            } else {
                client.mouse.lockCursor();
            }
        }
    }
}
