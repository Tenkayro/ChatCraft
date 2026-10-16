package de.tenkayro.chatcraft.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tenkayro.chatcraft.ChatCraftClient;
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
    private final Deque<Message> messages = new ArrayDeque<>();
    private final Map<String, Emote> emotes = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ChatCraft-7TV");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String loadedRoom = "";

    public void accept(String user, String message, int color, String badgeTags, String roomId) {
        if (roomId != null && !roomId.isBlank() && !roomId.equals(loadedRoom)) loadEmotes(roomId);
        synchronized (messages) {
            messages.addLast(new Message(System.currentTimeMillis(), user, message, color, badgeCodes(badgeTags)));
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
        int y = client.getWindow().getScaledHeight() - 44 - visible.size() * 14;
        for (Message message : visible) {
            renderLine(context, client, message, 4, y);
            y += 14;
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
                item.put("badges", message.badges);
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
        int width = Math.min(client.getWindow().getScaledWidth() - 8, measureLine(client, message));
        context.fill(x - 2, y - 2, x + width + 3, y + 12, 0xA0000000);
        for (String badge : message.badges) {
            context.drawTextWithShadow(client.textRenderer, badge, x, y, 0xFFD166);
            x += client.textRenderer.getWidth(badge) + 2;
        }
        context.drawTextWithShadow(client.textRenderer, message.user, x, y, message.color);
        x += client.textRenderer.getWidth(message.user) + 2;
        context.drawTextWithShadow(client.textRenderer, ":", x, y, 0xFFFFFFFF);
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
            int emoteWidth = Math.max(10, Math.min(28, Math.round(12.0F * emote.width / emote.height)));
            if (texture != null) context.drawTexture(texture, x, y - 2, 0, 0, emoteWidth, 12, emote.width, emote.height);
            else context.drawTextWithShadow(client.textRenderer, word, x, y, 0xFFB9A3FF);
            x += (texture != null ? emoteWidth : client.textRenderer.getWidth(word)) + 3;
        }
    }

    private int measureLine(MinecraftClient client, Message message) {
        int width = client.textRenderer.getWidth(message.user + ": " + message.text);
        for (String badge : message.badges) width += client.textRenderer.getWidth(badge) + 2;
        return width;
    }

    private void loadEmotes(String roomId) {
        loadedRoom = roomId;
        emotes.clear();
        loader.execute(() -> {
            try {
                readEmoteSet("https://7tv.io/v3/emote-sets/global");
                readChannelSet("https://7tv.io/v3/users/twitch/" + roomId);
            } catch (Exception exception) {
                ChatCraftClient.LOGGER.debug("Could not load 7TV emotes: {}", exception.toString());
            }
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
            String staticName = string(file, "static_name", name);
            int width = number(file, "width", 32);
            int height = number(file, "height", 32);
            if (name.equals("1x.png")) return new ImageChoice(name, name, width, height);
            if (name.equals("1x.gif")) gif = new ImageChoice(staticName, name, width, height);
        }
        return gif;
    }

    private void ensureTexture(Emote emote) {
        if (emote.texture != null || emote.loading) return;
        emote.loading = true;
        loader.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(emote.imageUrl)).timeout(Duration.ofSeconds(8)).GET().build();
                byte[] bytes = http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
                NativeImage image = emote.imageUrl.endsWith(".png") ? NativeImage.read(bytes) : fromBuffered(ImageIO.read(new ByteArrayInputStream(bytes)));
                MinecraftClient.getInstance().execute(() -> {
                    emote.width = image.getWidth();
                    emote.height = image.getHeight();
                    emote.texture = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(
                            "chatcraft_7tv_" + emote.id, new NativeImageBackedTexture(image));
                });
            } catch (Exception exception) {
                ChatCraftClient.LOGGER.debug("Could not load 7TV emote {}: {}", emote.id, exception.toString());
            }
        });
    }

    private JsonObject requestJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("7TV returned " + response.statusCode());
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

    private static List<String> badgeCodes(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String badge : tags.split(",")) {
            String name = badge.split("/", 2)[0];
            String code = switch (name) {
                case "broadcaster" -> "[B]";
                case "moderator" -> "[M]";
                case "vip" -> "[V]";
                case "subscriber", "founder" -> "[S]";
                case "premium", "turbo" -> "[P]";
                default -> "";
            };
            if (!code.isEmpty()) result.add(code);
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject object, String key, int fallback) {
        try { return object.get(key).getAsInt(); } catch (Exception ignored) { return fallback; }
    }

    private record Message(long timestamp, String user, String text, int color, List<String> badges) { }
    private record ImageChoice(String file, String browserFile, int width, int height) { }

    private static final class Emote {
        private final String id;
        private final String imageUrl;
        private final String browserUrl;
        private volatile int width;
        private volatile int height;
        private volatile boolean loading;
        private volatile Identifier texture;

        private Emote(String id, String imageUrl, String browserFile, int width, int height) {
            this.id = id;
            this.imageUrl = imageUrl;
            this.browserUrl = imageUrl.substring(0, imageUrl.lastIndexOf('/') + 1) + browserFile;
            this.width = width;
            this.height = height;
        }
    }
}
