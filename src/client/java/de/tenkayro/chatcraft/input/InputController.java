package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.camera.CameraController;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import de.tenkayro.chatcraft.mixin.MinecraftClientInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class InputController {
    private final ActionQueue queue;
    private final CameraController camera = new CameraController();
    private final GuiSlotMapper slots = new GuiSlotMapper();
    private final Map<KeyBinding, Long> heldUntil = new HashMap<>();
    private long tick;

    public InputController(ActionQueue queue) {
        this.queue = queue;
    }

    public void tick(MinecraftClient client) {
        tick++;
        try {
            releaseExpired();
            int budget = 16;
            ChatAction action;
            while (budget-- > 0 && (action = queue.poll()) != null) execute(client, action);
            if (client.player == null) releaseAll();
        } catch (RuntimeException exception) {
            releaseAll();
            ChatCraftClient.LOGGER.error("Failed to execute a queued action; all simulated keys were released", exception);
        }
    }

    public void releaseAll() {
        heldUntil.keySet().forEach(key -> key.setPressed(false));
        heldUntil.clear();
    }

    private void releaseExpired() {
        Iterator<Map.Entry<KeyBinding, Long>> iterator = heldUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<KeyBinding, Long> entry = iterator.next();
            if (entry.getValue() <= tick) {
                entry.getKey().setPressed(false);
                iterator.remove();
            }
        }
    }

    private void execute(MinecraftClient client, ChatAction action) {
        if (action.type() == ChatAction.Type.RELEASE_ALL) {
            releaseAll();
            return;
        }
        if (client.player == null) return;
        int duration = ChatCraftConfig.get().movementDurationTicks;
        float step = ChatCraftConfig.get().cameraStepDegrees;
        switch (action.type()) {
            case FORWARD -> hold(client.options.forwardKey, duration);
            case BACK -> hold(client.options.backKey, duration);
            case LEFT -> hold(client.options.leftKey, duration);
            case RIGHT -> hold(client.options.rightKey, duration);
            case JUMP -> hold(client.options.jumpKey, duration);
            case SNEAK -> hold(client.options.sneakKey, duration);
            case SPRINT -> hold(client.options.sprintKey, duration);
            case INVENTORY -> {
                if (client.currentScreen == null) client.setScreen(new InventoryScreen(client.player));
            }
            case DROP -> client.player.dropSelectedItem(false);
            case HOTBAR -> client.player.getInventory().selectedSlot = Integer.parseInt(action.argument()) - 1;
            case ATTACK -> ((MinecraftClientInvoker) client).chatcraft$doAttack();
            case USE -> ((MinecraftClientInvoker) client).chatcraft$doItemUse();
            case CAMERA_LEFT -> camera.turn(client, -step, 0);
            case CAMERA_RIGHT -> camera.turn(client, step, 0);
            case CAMERA_UP -> camera.turn(client, 0, -step);
            case CAMERA_DOWN -> camera.turn(client, 0, step);
            case RESPAWN -> {
                if (client.currentScreen instanceof DeathScreen) client.player.requestRespawn();
            }
            case SLOT_LEFT -> clickSlot(client, action.argument(), 0, SlotActionType.PICKUP);
            case SLOT_RIGHT -> clickSlot(client, action.argument(), 1, SlotActionType.PICKUP);
            case SLOT_SHIFT -> clickSlot(client, action.argument(), 0, SlotActionType.QUICK_MOVE);
            default -> { }
        }
    }

    private void hold(KeyBinding key, int duration) {
        key.setPressed(true);
        heldUntil.merge(key, tick + duration, Math::max);
    }

    private void clickSlot(MinecraftClient client, String label, int button, SlotActionType actionType) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || client.interactionManager == null) return;
        Slot slot = slots.find(screen, label);
        if (slot != null) {
            client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, button, actionType, client.player);
        }
    }
}
