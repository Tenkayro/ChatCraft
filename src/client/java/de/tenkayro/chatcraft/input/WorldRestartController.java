package de.tenkayro.chatcraft.input;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import de.tenkayro.chatcraft.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.nio.file.Path;

/** Safely replaces only the currently open local singleplayer world. */
public final class WorldRestartController {
    private enum State { IDLE, WAITING_FOR_STOP, WAITING_FOR_CREATE_SCREEN, WAITING_FOR_WORLD }

    private final ActivityLog activity;
    private State state = State.IDLE;
    private String directoryName;
    private String worldName;
    private long deadline;

    public WorldRestartController(ActivityLog activity) {
        this.activity = activity;
    }

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public String start(MinecraftClient client) {
        if (isRunning()) return "skipped: world restart already running";
        MinecraftServer server = client.getServer();
        if (server == null || client.world == null || !client.isIntegratedServerRunning()) {
            return "skipped: restart only works in a local singleplayer world";
        }

        Path saves = client.getLevelStorage().getSavesDirectory().toAbsolutePath().normalize();
        Path world = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        if (!world.startsWith(saves) || world.equals(saves) || world.getParent() == null
                || !world.getParent().equals(saves)) {
            ChatCraftClient.LOGGER.error("Refusing to restart world outside the direct saves directory: {}", world);
            return "refused: unsafe world directory";
        }

        directoryName = world.getFileName().toString();
        worldName = server.getSaveProperties().getLevelName();
        state = State.WAITING_FOR_STOP;
        deadline = System.currentTimeMillis() + 30_000L;
        activity.add("admin", "", ".cmd restart", worldName, "saving and closing current world");
        client.disconnect(new TitleScreen());
        return "restarting local world";
    }

    public void tick(MinecraftClient client) {
        if (state == State.IDLE) return;
        if (System.currentTimeMillis() > deadline) {
            fail("timed out while restarting world", null);
            return;
        }
        try {
            switch (state) {
                case WAITING_FOR_STOP -> waitForStop(client);
                case WAITING_FOR_CREATE_SCREEN -> configureCreateScreen(client);
                case WAITING_FOR_WORLD -> finishWhenJoined(client);
                default -> { }
            }
        } catch (Exception exception) {
            fail("world restart failed: " + exception.getMessage(), exception);
        }
    }

    private void waitForStop(MinecraftClient client) throws Exception {
        MinecraftServer server = client.getServer();
        if (client.world != null || (server != null && !server.isStopped())) return;

        LevelStorage storage = client.getLevelStorage();
        Path saves = storage.getSavesDirectory().toAbsolutePath().normalize();
        Path target = storage.resolve(directoryName).toAbsolutePath().normalize();
        if (!target.startsWith(saves) || target.equals(saves) || target.getParent() == null
                || !target.getParent().equals(saves)) {
            throw new IOException("unsafe world directory rejected");
        }
        try (LevelStorage.Session session = storage.createSession(directoryName)) {
            session.deleteSessionLock();
        }
        activity.add("admin", "", ".cmd restart", directoryName, "old world deleted; creating replacement");
        CreateWorldScreen.create(client, new TitleScreen());
        state = State.WAITING_FOR_CREATE_SCREEN;
        deadline = System.currentTimeMillis() + 60_000L;
    }

    private void configureCreateScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof CreateWorldScreen screen)) return;
        WorldCreator creator = screen.getWorldCreator();
        creator.setWorldName(worldName == null || worldName.isBlank() ? "ChatCraft" : worldName);
        creator.setGameMode(WorldCreator.Mode.SURVIVAL);
        creator.setDifficulty(Difficulty.EASY);
        creator.setCheatsEnabled(true);
        GameRules rules = creator.getGameRules();
        rules.get(GameRules.KEEP_INVENTORY).set(true, null);
        rules.get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, null);
        creator.setGameRules(rules);
        ((CreateWorldScreenInvoker) screen).chatcraft$createLevel();
        state = State.WAITING_FOR_WORLD;
        deadline = System.currentTimeMillis() + 120_000L;
    }

    private void finishWhenJoined(MinecraftClient client) {
        if (client.world == null || client.player == null || !client.isIntegratedServerRunning()) return;
        activity.add("admin", "", ".cmd restart", worldName,
                "new Survival/Easy world ready; cheats, keepInventory and immediate respawn enabled");
        clear();
    }

    private void fail(String message, Throwable exception) {
        if (exception == null) ChatCraftClient.LOGGER.error(message);
        else ChatCraftClient.LOGGER.error(message, exception);
        activity.add("admin", "", ".cmd restart", worldName, message);
        clear();
    }

    private void clear() {
        state = State.IDLE;
        directoryName = null;
        worldName = null;
        deadline = 0L;
    }
}
