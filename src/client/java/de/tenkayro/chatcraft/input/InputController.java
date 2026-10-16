package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.camera.CameraController;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import de.tenkayro.chatcraft.gui.GuiSlotMapper;
import de.tenkayro.chatcraft.mixin.MinecraftClientInvoker;
import de.tenkayro.chatcraft.overlay.SlotOverlayRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class InputController {
    private final ActionQueue queue;
    private final ActivityLog activity;
    private final CameraController camera = new CameraController();
    private final GuiSlotMapper slots = new GuiSlotMapper();
    private final Map<KeyBinding, Long> heldUntil = new HashMap<>();
    private long tick;

    public InputController(ActionQueue queue, ActivityLog activity) {
        this.queue = queue;
        this.activity = activity;
    }

    public void tick(MinecraftClient client) {
        tick++;
        try {
            releaseExpired();
            int budget = 16;
            ChatAction action;
            while (budget-- > 0 && (action = queue.poll()) != null) {
                try {
                    String result = execute(client, action);
                    activity.add("action", "", "", describe(action), result);
                } catch (RuntimeException exception) {
                    activity.add("action", "", "", describe(action), "error: " + exception.getClass().getSimpleName());
                    throw exception;
                }
            }
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

    private String execute(MinecraftClient client, ChatAction action) {
        if (action.type() == ChatAction.Type.RELEASE_ALL) {
            releaseAll();
            return "released all keys";
        }
        if (client.player == null) return "skipped: no player";
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
                if (client.currentScreen != null) return "skipped: screen already open";
                client.setScreen(new InventoryScreen(client.player));
            }
            case DROP -> client.player.dropSelectedItem(false);
            case HOTBAR -> selectHotbar(client, Integer.parseInt(action.argument()) - 1);
            case ATTACK -> ((MinecraftClientInvoker) client).chatcraft$doAttack();
            case USE -> ((MinecraftClientInvoker) client).chatcraft$doItemUse();
            case CAMERA_LEFT -> camera.turn(client, -step, 0);
            case CAMERA_RIGHT -> camera.turn(client, step, 0);
            case CAMERA_UP -> camera.turn(client, 0, -step);
            case CAMERA_DOWN -> camera.turn(client, 0, step);
            case RESPAWN -> {
                if (client.currentScreen instanceof DeathScreen) client.player.requestRespawn();
            }
            case SLOT_LEFT -> { return clickSlot(client, action.argument(), 0, SlotActionType.PICKUP); }
            case SLOT_RIGHT -> { return clickSlot(client, action.argument(), 1, SlotActionType.PICKUP); }
            case SLOT_SHIFT -> { return clickSlot(client, action.argument(), 0, SlotActionType.QUICK_MOVE); }
            default -> { }
        }
        return "executed";
    }

    private void hold(KeyBinding key, int duration) {
        key.setPressed(true);
        heldUntil.merge(key, tick + duration, Math::max);
    }

    private static void selectHotbar(MinecraftClient client, int slot) {
        client.player.getInventory().selectedSlot = slot;
        client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private String clickSlot(MinecraftClient client, String label, int button, SlotActionType actionType) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || client.interactionManager == null) {
            return "skipped: no container open";
        }
        Slot slot = slots.find(screen, label);
        if (slot != null) {
            SlotOverlayRenderer.moveCursorTo(screen, slot);
            client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, button, actionType, client.player);
            return "clicked slot " + label;
        }
        return "skipped: slot not found";
    }

    private static String describe(ChatAction action) {
        return action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
    }
}
