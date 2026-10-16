package de.tenkayro.chatcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TwitchChatFeed {
    private static final long MESSAGE_LIFETIME = 15_000L;
    private static final int LINE_HEIGHT = 12;
    private static final int BADGE_SIZE = 8;
    private static final int BADGE_GAP = 2;
    private static final int EMOTE_HEIGHT = 10;
    private final Deque<Message> messages = new ArrayDeque<>();
    private final Map<String, Emote> emotes = new ConcurrentHashMap<>();
    private final Map<String, Badge> badges = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ChatCraft-7TV");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String loadedRoom = "";

    public void accept(String user, String message, int color, String badgeTags, String roomId) {
        if (roomId != null && roomId.matches("[0-9]+") && !roomId.equals(loadedRoom)) loadAssets(roomId);
        synchronized (messages) {
            messages.addLast(new Message(System.currentTimeMillis(), user, message, color, parseBadges(badgeTags)));
            while (messages.size() > 100) messages.removeFirst();
        }
    }

    public void render(DrawContext context, RenderTickCounter ignored) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        boolean chatOpen = client.currentScreen instanceof ChatScreen;
        long now = System.currentTimeMillis();
        List<Message> visible = new ArrayList<>();
        synchronized (messages) {
            for (Message message : messages) {
                if (chatOpen || now - message.timestamp <= MESSAGE_LIFETIME) visible.add(message);
            }
        }
        int maximum = chatOpen ? 12 : 6;
        if (visible.size() > maximum) visible = visible.subList(visible.size() - maximum, visible.size());
        int y = client.getWindow().getScaledHeight() - 44 - visible.size() * LINE_HEIGHT;
        int panelWidth = 0;
        for (Message message : visible) panelWidth = Math.max(panelWidth, measureLine(client, message));
        if (!visible.isEmpty()) {
            context.fill(2, y - 2, Math.min(client.getWindow().getScaledWidth() - 2, panelWidth + 8),
                    y + visible.size() * LINE_HEIGHT - 1, 0x70000000);
        }
        for (Message message : visible) {
            renderLine(context, client, message, 4, y);
            y += LINE_HEIGHT;
        }
    }

    public synchronized void close() {
        loader.shutdownNow();
    }

    public List<Map<String, Object>> webMessages() {
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (messages) {
            for (Message message : messages) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timestamp", message.timestamp);
                item.put("user", message.user);
                item.put("message", message.text);
                item.put("color", String.format("#%06X", message.color));
                item.put("badges", message.badges.stream()
                        .map(ref -> badges.get(ref.key()))
                        .filter(java.util.Objects::nonNull)
                        .map(badge -> badge.imageUrl)
                        .toList());
                result.add(item);
            }
        }
        return result;
    }

    public Map<String, String> webEmotes() {
        Map<String, String> result = new LinkedHashMap<>();
        emotes.forEach((name, emote) -> result.put(name, emote.browserUrl));
        return result;
    }

    private void renderLine(DrawContext context, MinecraftClient client, Message message, int x, int y) {
        for (BadgeRef reference : message.badges) {
            Badge badge = badges.get(reference.key());
            if (badge == null) continue;
            ensureBadgeTexture(badge);
            if (badge.texture != null) {
                context.drawTexture(badge.texture, x, y + 1, 0, 0, BADGE_SIZE, BADGE_SIZE,
                        badge.width, badge.height);
                x += BADGE_SIZE + BADGE_GAP;
            }
        }
        context.drawTextWithShadow(client.textRenderer, message.user, x, y, message.color);
        x += client.textRenderer.getWidth(message.user);
        context.drawTextWithShadow(client.textRenderer, ": ", x, y, 0xFFFFFFFF);
        x += client.textRenderer.getWidth(": ");
        int limit = client.getWindow().getScaledWidth() - 6;
        for (String word : message.text.split(" ")) {
            if (x >= limit) break;
            Emote emote = emotes.get(word);
            if (emote == null) {
                String text = word + " ";
                context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
                x += client.textRenderer.getWidth(text);
                continue;
            }
            ensureTexture(emote);
            Identifier texture = emote.texture;
            int emoteWidth = Math.max(8, Math.min(24,
                    Math.round((float) EMOTE_HEIGHT * emote.width / emote.height)));
            if (texture != null) context.drawTexture(texture, x, y, 0, 0, emoteWidth, EMOTE_HEIGHT,
                    emote.width, emote.height);
            else context.drawTextWithShadow(client.textRenderer, word, x, y, 0xFFB9A3FF);
            x += (texture != null ? emoteWidth : client.textRenderer.getWidth(word)) + 3;
        }
    }

    private int measureLine(MinecraftClient client, Message message) {
        int width = client.textRenderer.getWidth(message.user + ": ");
        for (BadgeRef reference : message.badges) {
            if (badges.containsKey(reference.key())) width += BADGE_SIZE + BADGE_GAP;
        }
        for (String word : message.text.split(" ")) {
            Emote emote = emotes.get(word);
            width += emote == null ? client.textRenderer.getWidth(word + " ")
                    : Math.max(8, Math.min(24,
                            Math.round((float) EMOTE_HEIGHT * emote.width / emote.height))) + 3;
        }
        return width;
    }

    private void loadAssets(String roomId) {
        loadedRoom = roomId;
        emotes.clear();
        badges.clear();
        loader.execute(() -> {
            try {
                readEmoteSet("https://7tv.io/v3/emote-sets/global");
                readChannelSet("https://7tv.io/v3/users/twitch/" + roomId);
            } catch (Exception exception) {
                ChatCraftClient.LOGGER.debug("Could not load 7TV emotes: {}", exception.toString());
            }
            loadTwitchBadges(roomId);
        });
    }

    private void readChannelSet(String url) throws Exception {
        JsonObject user = requestJson(url);
        if (user.has("emote_set") && user.get("emote_set").isJsonObject()) readEmotes(user.getAsJsonObject("emote_set"));
    }

    private void readEmoteSet(String url) throws Exception {
        readEmotes(requestJson(url));
    }

    private void readEmotes(JsonObject set) {
        if (!set.has("emotes") || !set.get("emotes").isJsonArray()) return;
        for (JsonElement element : set.getAsJsonArray("emotes")) {
            JsonObject active = element.getAsJsonObject();
            JsonObject data = active.has("data") ? active.getAsJsonObject("data") : active;
            String name = string(active, "name", string(data, "name", ""));
            String id = string(data, "id", string(active, "id", ""));
            if (name.isBlank() || id.isBlank() || !data.has("host")) continue;
            JsonObject host = data.getAsJsonObject("host");
            String base = string(host, "url", "");
            if (base.startsWith("//")) base = "https:" + base;
            ImageChoice image = chooseImage(host.getAsJsonArray("files"));
            if (image != null) emotes.put(name, new Emote(id, base + "/" + image.file, image.browserFile,
                    image.width, image.height));
        }
    }

    private static ImageChoice chooseImage(JsonArray files) {
        ImageChoice gif = null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String name = string(file, "name", "");
            int width = number(file, "width", 32);
            int height = number(file, "height", 32);
            if (name.equals("1x.png")) return new ImageChoice(name, name, width, height);
            if (name.equals("1x.gif")) gif = new ImageChoice(name, name, width, height);
        }
        return gif;
    }

    private void ensureTexture(Emote emote) {
        if (emote.texture != null || emote.loading || System.nanoTime() < emote.retryAfter) return;
        emote.loading = true;
        loader.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(emote.imageUrl)).timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new java.io.IOException("CDN returned " + response.statusCode());
                byte[] bytes = response.body();
                NativeImage image = emote.imageUrl.endsWith(".png") ? NativeImage.read(bytes) : fromBuffered(ImageIO.read(new ByteArrayInputStream(bytes)));
                MinecraftClient.getInstance().execute(() -> {
                    emote.width = image.getWidth();
                    emote.height = image.getHeight();
                    emote.texture = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(
                            "chatcraft_7tv_" + emote.id, new NativeImageBackedTexture(image));
                });
            } catch (Exception exception) {
                emote.loading = false;
                emote.retryAfter = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                ChatCraftClient.LOGGER.debug("Could not load 7TV emote {}: {}", emote.id, exception.toString());
            }
        });
    }

    private void loadTwitchBadges(String roomId) {
        ChatCraftConfig config = ChatCraftConfig.get();
        String token = config.oauthToken == null ? "" : config.oauthToken.trim();
        if (token.regionMatches(true, 0, "oauth:", 0, 6)) token = token.substring(6);
        if (token.isBlank() || config.twitchClientId == null || config.twitchClientId.isBlank()) return;
        try {
            readBadges(requestTwitchJson("https://api.twitch.tv/helix/chat/badges/global", token,
                    config.twitchClientId));
            readBadges(requestTwitchJson("https://api.twitch.tv/helix/chat/badges?broadcaster_id=" + roomId,
                    token, config.twitchClientId));
        } catch (Exception exception) {
            ChatCraftClient.LOGGER.debug("Could not load Twitch badges: {}", exception.toString());
        }
    }

    private void readBadges(JsonObject response) {
        if (!response.has("data") || !response.get("data").isJsonArray()) return;
        for (JsonElement setElement : response.getAsJsonArray("data")) {
            JsonObject set = setElement.getAsJsonObject();
            String setId = string(set, "set_id", "");
            if (setId.isBlank() || !set.has("versions") || !set.get("versions").isJsonArray()) continue;
            for (JsonElement versionElement : set.getAsJsonArray("versions")) {
                JsonObject version = versionElement.getAsJsonObject();
                String versionId = string(version, "id", "");
                String imageUrl = string(version, "image_url_1x", "");
                if (!versionId.isBlank() && imageUrl.startsWith("https://")) {
                    badges.put(setId + "/" + versionId, new Badge(setId + "_" + versionId, imageUrl));
                }
            }
        }
    }

    private void ensureBadgeTexture(Badge badge) {
        if (badge.texture != null || badge.loading || System.nanoTime() < badge.retryAfter) return;
        badge.loading = true;
        loader.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(badge.imageUrl)).timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new java.io.IOException("Badge CDN returned " + response.statusCode());
                NativeImage image = NativeImage.read(response.body());
                MinecraftClient.getInstance().execute(() -> {
                    badge.width = image.getWidth();
                    badge.height = image.getHeight();
                    badge.texture = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(
                            "chatcraft_twitch_badge_" + badge.id.replace('/', '_'), new NativeImageBackedTexture(image));
                });
            } catch (Exception exception) {
                badge.loading = false;
                badge.retryAfter = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                ChatCraftClient.LOGGER.debug("Could not load Twitch badge {}: {}", badge.id, exception.toString());
            }
        });
    }

    private JsonObject requestJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("7TV returned " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject requestTwitchJson(String url, String token, String clientId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", clientId)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("Twitch returned " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static NativeImage fromBuffered(BufferedImage source) throws java.io.IOException {
        if (source == null) throw new java.io.IOException("Unsupported emote image");
        NativeImage image = new NativeImage(source.getWidth(), source.getHeight(), true);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int abgr = (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >>> 16) | ((argb & 0x000000FF) << 16);
                image.setColor(x, y, abgr);
            }
        }
        return image;
    }

    private static List<BadgeRef> parseBadges(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        List<BadgeRef> result = new ArrayList<>();
        for (String badge : tags.split(",")) {
            String[] parts = badge.split("/", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                result.add(new BadgeRef(parts[0], parts[1]));
            }
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject object, String key, int fallback) {
        try { return object.get(key).getAsInt(); } catch (Exception ignored) { return fallback; }
    }

    private record Message(long timestamp, String user, String text, int color, List<BadgeRef> badges) { }
    private record BadgeRef(String setId, String version) {
        private String key() { return setId + "/" + version; }
    }
    private record ImageChoice(String file, String browserFile, int width, int height) { }

    private static final class Emote {
        private final String id;
        private final String imageUrl;
        private final String browserUrl;
        private volatile int width;
        private volatile int height;
        private volatile boolean loading;
        private volatile long retryAfter;
        private volatile Identifier texture;

        private Emote(String id, String imageUrl, String browserFile, int width, int height) {
            this.id = id;
            this.imageUrl = imageUrl;
            this.browserUrl = imageUrl.substring(0, imageUrl.lastIndexOf('/') + 1) + browserFile;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Badge {
        private final String id;
        private final String imageUrl;
        private volatile int width = 18;
        private volatile int height = 18;
        private volatile boolean loading;
        private volatile long retryAfter;
        private volatile Identifier texture;

        private Badge(String id, String imageUrl) {
            this.id = id;
            this.imageUrl = imageUrl;
        }
    }
}
