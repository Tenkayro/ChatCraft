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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
    private BreakTask breakTask;
    private PlaceTask placeTask;
    private boolean sneakToggled;
    private long busyUntilTick;
    private long tick;

    public InputController(ActionQueue queue, ActivityLog activity) {
        this.queue = queue;
        this.activity = activity;
    }

    public void tick(MinecraftClient client) {
        tick++;
        try {
            camera.tick(client);
            releaseExpired();
            updateWorldActions(client);
            if (!isBusy()) {
                ChatAction action = queue.poll();
                if (action != null) {
                    try {
                        String result = execute(client, action);
                        activity.add("action", "", "", describe(action), result);
                    } catch (RuntimeException exception) {
                        activity.add("action", "", "", describe(action), "error: " + exception.getClass().getSimpleName());
                        throw exception;
                    }
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
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.sneakKey.setPressed(false);
        sneakToggled = false;
        busyUntilTick = 0;
        camera.cancel();
        cancelWorldActions();
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
        int duration = action.durationTicks() > 0
                ? action.durationTicks()
                : ChatCraftConfig.get().movementDurationTicks;
        float step = ChatCraftConfig.get().cameraStepDegrees;
        switch (action.type()) {
            case FORWARD -> hold(client.options.forwardKey, duration);
            case BACK -> hold(client.options.backKey, duration);
            case LEFT -> hold(client.options.leftKey, duration);
            case RIGHT -> hold(client.options.rightKey, duration);
            case JUMP -> hold(client.options.jumpKey, duration);
            case SNEAK -> toggleSneak(client.options.sneakKey);
            case SPRINT -> hold(client.options.sprintKey, duration);
            case INVENTORY -> {
                if (client.currentScreen == null) client.setScreen(new InventoryScreen(client.player));
                else client.currentScreen.close();
            }
            case CLOSE_SCREEN -> {
                if (client.currentScreen == null) return "skipped: no screen open";
                client.currentScreen.close();
            }
            case DROP -> client.player.dropSelectedItem(false);
            case HOTBAR -> selectHotbar(client, Integer.parseInt(action.argument()) - 1);
            case ATTACK -> ((MinecraftClientInvoker) client).chatcraft$doAttack();
            case USE -> ((MinecraftClientInvoker) client).chatcraft$doItemUse();
            case BREAK -> { return startBreaking(client); }
            case PLACE -> { return placeBlock(client); }
            case CAMERA_LEFT -> camera.turn(client, -step, 0);
            case CAMERA_RIGHT -> camera.turn(client, step, 0);
            case CAMERA_UP -> camera.turn(client, 0, -step);
            case CAMERA_DOWN -> camera.turn(client, 0, step);
            case CAMERA_AROUND -> camera.turn(client, 180.0f, 0);
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
        busyUntilTick = Math.max(busyUntilTick, tick + duration);
    }

    private void toggleSneak(KeyBinding key) {
        sneakToggled = !sneakToggled;
        key.setPressed(sneakToggled);
    }

    private boolean isBusy() {
        return breakTask != null || placeTask != null || camera.isTurning() || tick < busyUntilTick;
    }

    private static void selectHotbar(MinecraftClient client, int slot) {
        client.player.getInventory().selectedSlot = slot;
        client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private String startBreaking(MinecraftClient client) {
        if (client.currentScreen != null || client.world == null || client.interactionManager == null) {
            return "skipped: world is not ready";
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return "skipped: no block targeted";
        BlockPos position = hit.getBlockPos().toImmutable();
        BlockState state = client.world.getBlockState(position);
        if (state.isAir()) return "skipped: no block targeted";
        cancelWorldActions();
        if (!client.interactionManager.attackBlock(position, hit.getSide())) {
            return "skipped: block cannot be attacked";
        }
        client.options.attackKey.setPressed(true);
        client.player.swingHand(Hand.MAIN_HAND);
        breakTask = new BreakTask(position, hit.getSide(), state.getBlock(), tick + 400);
        return "breaking block";
    }

    private String placeBlock(MinecraftClient client) {
        if (client.currentScreen != null || client.world == null || client.interactionManager == null) {
            return "skipped: world is not ready";
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return "skipped: no block targeted";
        cancelWorldActions();
        for (Hand hand : Hand.values()) {
            ItemStack stack = client.player.getStackInHand(hand);
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            ItemPlacementContext placement = new ItemPlacementContext(client.player, hand, stack, hit);
            BlockPos position = placement.getBlockPos().toImmutable();
            BlockState previous = client.world.getBlockState(position);
            ActionResult result = client.interactionManager.interactBlock(client.player, hand, hit);
            if (!result.isAccepted()) continue;
            if (result.shouldSwingHand()) client.player.swingHand(hand);
            placeTask = new PlaceTask(position, blockItem.getBlock(), previous, tick + 40);
            return "placing block";
        }
        return "skipped: no placeable block in either hand";
    }

    private void updateWorldActions(MinecraftClient client) {
        if (breakTask != null) updateBreaking(client);
        if (placeTask != null) updatePlacement(client);
    }

    private void updateBreaking(MinecraftClient client) {
        BreakTask task = breakTask;
        if (client.world == null || client.player == null || client.interactionManager == null) {
            finishBreaking(client, "cancelled: world closed");
            return;
        }
        if (!client.world.getBlockState(task.position).isOf(task.originalBlock)) {
            finishBreaking(client, "confirmed: block broken");
            return;
        }
        if (tick >= task.expiresAtTick) {
            finishBreaking(client, "failed: break timed out");
            return;
        }
        client.interactionManager.updateBlockBreakingProgress(task.position, task.side);
        if ((tick & 3) == 0) client.player.swingHand(Hand.MAIN_HAND);
    }

    private void updatePlacement(MinecraftClient client) {
        PlaceTask task = placeTask;
        if (client.world == null || client.player == null) {
            finishPlacement("cancelled: world closed");
            return;
        }
        BlockState current = client.world.getBlockState(task.position);
        if (current.isOf(task.expectedBlock) && !current.equals(task.previousState)) {
            finishPlacement("confirmed: block placed");
            return;
        }
        if (tick >= task.expiresAtTick) finishPlacement("failed: placement not confirmed");
    }

    private void finishBreaking(MinecraftClient client, String result) {
        client.options.attackKey.setPressed(false);
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        breakTask = null;
        activity.add("action", "", "", "BREAK", result);
    }

    private void finishPlacement(String result) {
        placeTask = null;
        activity.add("action", "", "", "PLACE", result);
    }

    private void cancelWorldActions() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.attackKey.setPressed(false);
        if (breakTask != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        breakTask = null;
        placeTask = null;
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
        String description = action.argument().isBlank() ? action.type().name() : action.type().name() + " " + action.argument();
        return action.durationTicks() > 0 ? description + " (" + action.durationTicks() + " ticks)" : description;
    }

    private record BreakTask(BlockPos position, Direction side, Block originalBlock, long expiresAtTick) { }
    private record PlaceTask(BlockPos position, Block expectedBlock, BlockState previousState, long expiresAtTick) { }
}
