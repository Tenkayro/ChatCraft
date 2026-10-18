package de.tenkayro.chatcraft.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.command.CommandParser;
import de.tenkayro.chatcraft.command.CommandService;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import de.tenkayro.chatcraft.twitch.TwitchChatFeed;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Reads the authenticated channel's active YouTube live chat alongside Twitch. */
public final class YouTubeConnection {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR, AUTH_REQUIRED }

    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private final CommandService commands;
    private final ActionQueue queue;
    private final ActivityLog activity;
    private final TwitchChatFeed chatFeed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ChatCraft-YouTube");
        thread.setDaemon(true);
        return thread;
    });
    private volatile State state = State.DISCONNECTED;
    private volatile String detail = "Disconnected";
    private volatile boolean stopped = true;
    private volatile ScheduledFuture<?> task;
    private String liveChatId = "";
    private String pageToken = "";
    private boolean firstPage = true;

    public YouTubeConnection(CommandService commands, ActionQueue queue, ActivityLog activity,
                             TwitchChatFeed chatFeed) {
        this.commands = commands;
        this.queue = queue;
        this.activity = activity;
        this.chatFeed = chatFeed;
    }

    public synchronized void connect() {
        if (!stopped) return;
        ChatCraftConfig config = ChatCraftConfig.get();
        if (config.youtubeClientId.isBlank() || config.youtubeClientSecret.isBlank()
                || config.youtubeRefreshToken.isBlank()) {
            state = State.AUTH_REQUIRED;
            detail = "YouTube authorization required";
            return;
        }
        stopped = false;
        state = State.CONNECTING;
        detail = "Looking for an active YouTube broadcast";
        activity.add("system", "", "", "YouTube", "connecting");
        schedule(this::discoverAndPoll, 0);
    }

    public synchronized void disconnect() {
        stopped = true;
        if (task != null) task.cancel(true);
        task = null;
        liveChatId = "";
        pageToken = "";
        firstPage = true;
        state = State.DISCONNECTED;
        detail = "Disconnected";
        queue.offerPriority(new ChatAction(ChatAction.Type.RELEASE_ALL));
        activity.add("system", "", "", "YouTube", "disconnected");
    }

    public void close() {
        disconnect();
        executor.shutdownNow();
    }

    public State state() { return state; }
    public String detail() { return detail; }

    private void discoverAndPoll() {
        if (stopped) return;
        try {
            ensureAccessToken();
            if (liveChatId.isBlank()) discoverChat();
            poll();
        } catch (AuthException exception) {
            if (stopped) return;
            state = State.AUTH_REQUIRED;
            detail = exception.getMessage();
            stopped = true;
            activity.add("system", "", "", "YouTube", detail);
        } catch (Exception exception) {
            if (stopped) return;
            state = State.ERROR;
            detail = "YouTube error: " + exception.getMessage();
            ChatCraftClient.LOGGER.warn("YouTube live chat error", exception);
            activity.add("system", "", "", "YouTube", detail);
            if (ChatCraftConfig.get().autoReconnect) {
                liveChatId = "";
                pageToken = "";
                firstPage = true;
                schedule(this::discoverAndPoll, 10_000);
            } else stopped = true;
        }
    }

    private void discoverChat() throws Exception {
        JsonObject json = getJson(API + "liveBroadcasts?part=snippet&broadcastStatus=active&mine=true&maxResults=5");
        JsonArray items = json.getAsJsonArray("items");
        if (items == null || items.isEmpty()) {
            state = State.CONNECTING;
            detail = "Waiting for an active YouTube broadcast";
            schedule(this::discoverAndPoll, 15_000);
            return;
        }
        for (var element : items) {
            JsonObject snippet = element.getAsJsonObject().getAsJsonObject("snippet");
            if (snippet != null && snippet.has("liveChatId")) {
                liveChatId = snippet.get("liveChatId").getAsString();
                String title = snippet.has("title") ? snippet.get("title").getAsString() : "live stream";
                state = State.CONNECTED;
                detail = "Connected to YouTube: " + title;
                activity.add("system", "", "", "YouTube", detail);
                break;
            }
        }
        if (liveChatId.isBlank()) {
            detail = "Active broadcast has no live chat";
            schedule(this::discoverAndPoll, 15_000);
        }
    }

    private void poll() throws Exception {
        if (stopped || liveChatId.isBlank()) return;
        String url = API + "liveChat/messages?part=id,snippet,authorDetails&maxResults=200&liveChatId="
                + encode(liveChatId) + (pageToken.isBlank() ? "" : "&pageToken=" + encode(pageToken));
        JsonObject json = getJson(url);
        JsonArray items = json.getAsJsonArray("items");
        if (!firstPage && items != null) {
            for (var element : items) handleMessage(element.getAsJsonObject());
        }
        firstPage = false; // Do not execute old chat history on initial connection.
        pageToken = json.has("nextPageToken") ? json.get("nextPageToken").getAsString() : pageToken;
        long delay = json.has("pollingIntervalMillis") ? json.get("pollingIntervalMillis").getAsLong() : 5_000;
        state = State.CONNECTED;
        schedule(this::discoverAndPoll, Math.max(1_000, delay));
    }

    private void handleMessage(JsonObject item) {
        JsonObject snippet = item.getAsJsonObject("snippet");
        JsonObject author = item.getAsJsonObject("authorDetails");
        if (snippet == null || author == null || !"textMessageEvent".equals(string(snippet, "type"))) return;
        String message = CommandParser.sanitizeMessage(string(snippet, "displayMessage"));
        String user = string(author, "displayName");
        if (message.isBlank() || user.isBlank()) return;
        activity.add("chat", user, message, "YouTube", "received");
        chatFeed.accept(user, message, 0xFF0000, "YouTube");
        boolean privileged = bool(author, "isChatOwner") || bool(author, "isChatModerator");
        if (CommandService.isMinecraftCommand(message)) commands.submitMinecraftCommand(user, message, privileged);
        else commands.submit(user, message, false);
    }

    private JsonObject getJson(String url) throws Exception {
        ChatCraftConfig config = ChatCraftConfig.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + config.youtubeAccessToken).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new AuthException("YouTube authorization expired");
        if (response.statusCode() / 100 != 2) throw new Exception(apiError(response));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void ensureAccessToken() throws Exception {
        ChatCraftConfig config = ChatCraftConfig.get();
        if (!config.youtubeAccessToken.isBlank()
                && System.currentTimeMillis() < config.youtubeAccessTokenExpiresAt - 60_000) return;
        String body = "client_id=" + encode(config.youtubeClientId)
                + "&client_secret=" + encode(config.youtubeClientSecret)
                + "&refresh_token=" + encode(config.youtubeRefreshToken)
                + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(TOKEN_URI).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new AuthException("YouTube token refresh failed");
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        synchronized (config) {
            config.youtubeAccessToken = string(json, "access_token");
            config.youtubeAccessTokenExpiresAt = System.currentTimeMillis()
                    + (json.has("expires_in") ? json.get("expires_in").getAsLong() * 1000 : 3_600_000);
            config.save();
        }
    }

    private synchronized void schedule(Runnable runnable, long delayMillis) {
        if (stopped) return;
        task = executor.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static String apiError(HttpResponse<String> response) {
        try {
            JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
            return "API " + response.statusCode() + ": " + string(error, "message");
        } catch (Exception ignored) {
            return "API request failed (" + response.statusCode() + ")";
        }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).getAsBoolean();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static final class AuthException extends Exception {
        private AuthException(String message) { super(message); }
    }
}
