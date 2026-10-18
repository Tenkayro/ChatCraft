package de.tenkayro.chatcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tenkayro.chatcraft.ChatCraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChatCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("chatcraft.json");
    private static ChatCraftConfig instance;

    public String username = "";
    public String twitchClientId = "";
    public transient String oauthToken = "";
    private String encryptedOauthToken = "";
    public String youtubeClientId = "";
    public transient String youtubeClientSecret = "";
    public transient String youtubeAccessToken = "";
    public transient String youtubeRefreshToken = "";
    public long youtubeAccessTokenExpiresAt = 0;
    private String encryptedYoutubeClientSecret = "";
    private String encryptedYoutubeAccessToken = "";
    private String encryptedYoutubeRefreshToken = "";
    public int dashboardPort = 38473;
    public String commandPrefix = "";
    public boolean controlsEnabled = true;
    public boolean overlayEnabled = true;
    public boolean autoConnect = false;
    public boolean autoReconnect = true;
    public boolean keepRunningUnfocused = true;
    public boolean followFireworkEnabled = true;
    public boolean subTntEnabled = true;
    public boolean privilegedCommandsEnabled = true;
    public int movementDurationTicks = 10;
    public float cameraStepDegrees = 30.0f;
    public List<String> whitelist = new ArrayList<>();
    public List<String> blacklist = new ArrayList<>();

    public static synchronized ChatCraftConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static synchronized void replace(ChatCraftConfig config) {
        instance = config;
        instance.normalize();
        instance.save();
    }

    private static ChatCraftConfig load() {
        if (Files.isRegularFile(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                ChatCraftConfig config = GSON.fromJson(json, ChatCraftConfig.class);
                if (config != null) {
                    boolean migrated = false;
                    if ((config.username == null || config.username.isBlank())
                        && json.has("channel") && json.get("channel").isJsonPrimitive()) {
                        config.username = json.get("channel").getAsString();
                        migrated = true;
                    }
                    if (json.has("oauthToken") && json.get("oauthToken").isJsonPrimitive()) {
                        config.oauthToken = json.get("oauthToken").getAsString();
                        migrated = true;
                    } else if (config.encryptedOauthToken != null && !config.encryptedOauthToken.isBlank()) {
                        config.oauthToken = TokenVault.decrypt(config.encryptedOauthToken);
                    }
                    config.youtubeClientSecret = decrypt(config.encryptedYoutubeClientSecret);
                    config.youtubeAccessToken = decrypt(config.encryptedYoutubeAccessToken);
                    config.youtubeRefreshToken = decrypt(config.encryptedYoutubeRefreshToken);
                    config.normalize();
                    if (migrated) config.save();
                    return config;
                }
            } catch (Exception exception) {
                ChatCraftClient.LOGGER.error("Could not read ChatCraft config", exception);
            }
        }
        ChatCraftConfig config = new ChatCraftConfig();
        config.save();
        return config;
    }

    public synchronized void save() {
        normalize();
        try {
            encryptedOauthToken = TokenVault.encrypt(oauthToken);
            encryptedYoutubeClientSecret = TokenVault.encrypt(youtubeClientSecret);
            encryptedYoutubeAccessToken = TokenVault.encrypt(youtubeAccessToken);
            encryptedYoutubeRefreshToken = TokenVault.encrypt(youtubeRefreshToken);
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(this, writer);
            }
            Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            ChatCraftClient.LOGGER.error("Could not save ChatCraft config", exception);
        }
    }

    public boolean userAllowed(String user) {
        String normalized = user.toLowerCase(Locale.ROOT);
        if (blacklist.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals)) return false;
        return whitelist.isEmpty() || whitelist.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
    }

    private void normalize() {
        if (username == null) username = "";
        username = username.trim().toLowerCase(Locale.ROOT).replaceFirst("^[@#]", "");
        if (twitchClientId == null) twitchClientId = "";
        twitchClientId = twitchClientId.trim();
        if (oauthToken == null) oauthToken = "";
        if (youtubeClientId == null) youtubeClientId = "";
        youtubeClientId = youtubeClientId.trim();
        if (youtubeClientSecret == null) youtubeClientSecret = "";
        youtubeClientSecret = youtubeClientSecret.trim();
        if (youtubeAccessToken == null) youtubeAccessToken = "";
        if (youtubeRefreshToken == null) youtubeRefreshToken = "";
        dashboardPort = Math.max(1024, Math.min(65535, dashboardPort));
        if (commandPrefix == null) commandPrefix = "";
        if (whitelist == null) whitelist = new ArrayList<>();
        if (blacklist == null) blacklist = new ArrayList<>();
        movementDurationTicks = Math.max(1, Math.min(200, movementDurationTicks));
        cameraStepDegrees = Math.max(1.0f, Math.min(180.0f, cameraStepDegrees));
    }

    private static String decrypt(String value) throws Exception {
        return value == null || value.isBlank() ? "" : TokenVault.decrypt(value);
    }
}
