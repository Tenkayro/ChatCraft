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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Reads the authenticated channel's active YouTube live chat alongside Twitch. */
public final class YouTubeConnection {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, WAITING, ERROR, AUTH_REQUIRED }

    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final long MIN_CHAT_POLL_MILLIS = 15_000;
    private static final int RECENT_MESSAGE_LIMIT = 2_000;
    private static final long[] DISCOVERY_DELAYS_MILLIS = {15_000, 30_000, 60_000, 120_000, 300_000};
    private static final ZoneId YOUTUBE_QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
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
    private long generation;
    private volatile String liveChatId = "";
    private volatile String activeBroadcastId = "";
    private volatile String pageToken = "";
    private final ArrayDeque<String> recentMessageOrder = new ArrayDeque<>();
    private final Set<String> recentMessageIds = new HashSet<>();
    private volatile boolean firstPage = true;
    private int discoveryAttempt;
    private int failureAttempt;

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
        long session = ++generation;
        state = State.CONNECTING;
        detail = "Looking for an active YouTube broadcast";
        discoveryAttempt = 0;
        failureAttempt = 0;
        activity.add("system", "", "", "YouTube", "connecting");
        schedule(session, 0);
    }

    public synchronized void disconnect() {
        stopped = true;
        generation++;
        if (task != null) task.cancel(true);
        task = null;
        liveChatId = "";
        activeBroadcastId = "";
        pageToken = "";
        clearRecentMessages();
        firstPage = true;
        discoveryAttempt = 0;
        failureAttempt = 0;
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
    public String activeBroadcastId() { return activeBroadcastId; }

    /** Returns a valid OAuth token for other low-frequency YouTube read requests. */
    public synchronized String accessTokenForApi() throws Exception {
        ensureAccessToken();
        return ChatCraftConfig.get().youtubeAccessToken;
    }

    private void discoverAndPoll(long session) {
        if (!isCurrent(session)) return;
        try {
            ensureAccessToken();
            if (!isCurrent(session)) return;
            if (liveChatId.isBlank()) discoverChat(session);
            poll(session);
        } catch (AuthException exception) {
            if (!isCurrent(session)) return;
            state = State.AUTH_REQUIRED;
            detail = exception.getMessage();
            stopped = true;
            activity.add("system", "", "", "YouTube", detail);
            activity.error("YouTube authentication", exception.diagnostic, exception);
        } catch (ApiException exception) {
            if (!isCurrent(session)) return;
            handleApiError(exception, session);
        } catch (Exception exception) {
            if (!isCurrent(session)) return;
            long retryDelay = retryDelayMillis();
            state = ChatCraftConfig.get().autoReconnect ? State.WAITING : State.ERROR;
            detail = "YouTube connection problem; " + (ChatCraftConfig.get().autoReconnect
                    ? "retrying in " + formatDelay(retryDelay) : "automatic reconnect is disabled");
            ChatCraftClient.LOGGER.warn("YouTube live chat error", exception);
            activity.error("YouTube connection", detail, exception);
            if (ChatCraftConfig.get().autoReconnect) {
                schedule(session, retryDelay);
            } else stopped = true;
        }
    }

    private void discoverChat(long session) throws Exception {
        JsonObject json = getJson(API + "liveBroadcasts?part=snippet,status&mine=true&broadcastType=all&maxResults=50",
                "Discover active broadcast");
        JsonArray items = json.getAsJsonArray("items");
        boolean liveBroadcastFound = false;
        activeBroadcastId = "";
        if (items != null) {
            for (var element : items) {
                JsonObject broadcast = element.getAsJsonObject();
                JsonObject status = broadcast.getAsJsonObject("status");
                if (!"live".equals(string(status, "lifeCycleStatus"))) continue;

                liveBroadcastFound = true;
                activeBroadcastId = string(broadcast, "id");
                JsonObject snippet = broadcast.getAsJsonObject("snippet");
                if (snippet != null && snippet.has("liveChatId")) {
                    liveChatId = snippet.get("liveChatId").getAsString();
                    String title = snippet.has("title") ? snippet.get("title").getAsString() : "live stream";
                    state = State.CONNECTED;
                    detail = "Connected to YouTube: " + title;
                    discoveryAttempt = 0;
                    failureAttempt = 0;
                    activity.add("system", "", "", "YouTube", detail);
                    break;
                }
            }
        }
        if (liveChatId.isBlank()) {
            state = State.WAITING;
            detail = liveBroadcastFound
                    ? "Active YouTube broadcast has no live chat"
                    : "Waiting for an active YouTube broadcast";
            schedule(session, nextDiscoveryDelayMillis());
        }
    }

    private void poll(long session) throws Exception {
        if (!isCurrent(session) || liveChatId.isBlank()) return;
        String url = API + "liveChat/messages?part=id,snippet,authorDetails&maxResults=200&liveChatId="
                + encode(liveChatId) + (pageToken.isBlank() ? "" : "&pageToken=" + encode(pageToken));
        JsonObject json = getJson(url, "Read live chat messages");
        JsonArray items = json.getAsJsonArray("items");
        if (items != null) {
            for (var element : items) {
                try {
                    JsonObject item = element.getAsJsonObject();
                    String messageId = string(item, "id");
                    if (!messageId.isBlank() && !rememberMessage(messageId)) continue;
                    if (!firstPage) handleMessage(item);
                } catch (RuntimeException exception) {
                    activity.warn("YouTube message", "Skipped one malformed live chat message", exception);
                }
            }
        }
        firstPage = false; // Do not execute old chat history on initial connection.
        pageToken = json.has("nextPageToken") ? json.get("nextPageToken").getAsString() : pageToken;
        long delay = json.has("pollingIntervalMillis") ? json.get("pollingIntervalMillis").getAsLong() : 5_000;
        state = State.CONNECTED;
        failureAttempt = 0;
        schedule(session, Math.max(MIN_CHAT_POLL_MILLIS, delay));
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

    private synchronized boolean rememberMessage(String id) {
        if (!recentMessageIds.add(id)) return false;
        recentMessageOrder.addLast(id);
        while (recentMessageOrder.size() > RECENT_MESSAGE_LIMIT) {
            recentMessageIds.remove(recentMessageOrder.removeFirst());
        }
        return true;
    }

    private synchronized void clearRecentMessages() {
        recentMessageOrder.clear();
        recentMessageIds.clear();
    }

    private JsonObject getJson(String url, String operation) throws Exception {
        ChatCraftConfig config = ChatCraftConfig.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + config.youtubeAccessToken).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new AuthException("YouTube authorization expired",
                    operation + " failed\nHTTP status: 401\nGoogle request ID: " + requestId(response));
        }
        if (response.statusCode() / 100 != 2) throw apiError(response, operation);
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ApiException(response.statusCode(), "invalidResponse", operation,
                    requestId(response), "YouTube returned malformed JSON", exception);
        }
    }

    private synchronized void ensureAccessToken() throws Exception {
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
        if (response.statusCode() / 100 != 2) {
            String oauthReason = "not provided";
            try {
                JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject();
                String code = string(error, "error");
                String description = string(error, "error_description");
                oauthReason = code + (description.isBlank() ? "" : ": " + description);
            } catch (RuntimeException ignored) { }
            throw new AuthException("YouTube authorization must be renewed",
                    "Token refresh failed\nHTTP status: " + response.statusCode()
                            + "\nReason: " + oauthReason
                            + "\nGoogle request ID: " + requestId(response));
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new AuthException("YouTube returned an invalid token response",
                    "Token refresh returned malformed JSON\nHTTP status: " + response.statusCode(), exception);
        }
        if (string(json, "access_token").isBlank()) {
            throw new AuthException("YouTube did not return an access token",
                    "Token refresh succeeded but access_token was missing");
        }
        synchronized (config) {
            config.youtubeAccessToken = string(json, "access_token");
            config.youtubeAccessTokenExpiresAt = System.currentTimeMillis()
                    + (json.has("expires_in") ? json.get("expires_in").getAsLong() * 1000 : 3_600_000);
            config.save();
        }
    }

    private synchronized void schedule(long session, long delayMillis) {
        if (stopped || session != generation) return;
        try {
            task = executor.schedule(() -> discoverAndPoll(session), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!executor.isShutdown()) {
                state = State.ERROR;
                detail = "YouTube reconnect could not be scheduled";
                activity.error("YouTube scheduler", detail, exception);
            }
        }
    }

    private synchronized boolean isCurrent(long session) {
        return !stopped && session == generation;
    }

    private void handleApiError(ApiException exception, long session) {
        if (exception.isQuotaExceeded()) {
            state = State.WAITING;
            detail = "Daily YouTube quota exhausted; retrying " + quotaResetLabel();
            ChatCraftClient.LOGGER.warn("YouTube quota exhausted; pausing API requests until reset");
            activity.add("system", "", "", "YouTube", detail);
            activity.warn("YouTube quota", exception.diagnostic());
            schedule(session, millisUntilQuotaReset());
            return;
        }

        if (exception.isChatUnavailable()) {
            liveChatId = "";
            activeBroadcastId = "";
            pageToken = "";
            clearRecentMessages();
            firstPage = true;
            discoveryAttempt = 0;
            state = State.WAITING;
            detail = "YouTube live chat ended; waiting for the next broadcast";
            activity.add("system", "", "", "YouTube", detail);
            activity.info("YouTube live chat", exception.diagnostic());
            schedule(session, nextDiscoveryDelayMillis());
            return;
        }

        if (exception.isRateLimited()) {
            state = State.WAITING;
            detail = "YouTube rate limit reached; retrying shortly";
            activity.warn("YouTube rate limit", exception.diagnostic());
            schedule(session, Math.max(60_000, retryDelayMillis()));
            return;
        }

        state = State.ERROR;
        detail = "YouTube error: " + exception.getMessage();
        ChatCraftClient.LOGGER.warn("YouTube API error ({})", exception.reason, exception);
        activity.add("system", "", "", "YouTube", detail);
        activity.error("YouTube API", exception.diagnostic(), exception);
        if (ChatCraftConfig.get().autoReconnect) {
            schedule(session, retryDelayMillis());
        } else stopped = true;
    }

    private long nextDiscoveryDelayMillis() {
        int index = Math.min(discoveryAttempt++, DISCOVERY_DELAYS_MILLIS.length - 1);
        return DISCOVERY_DELAYS_MILLIS[index];
    }

    private long retryDelayMillis() {
        return Math.min(300_000L, 10_000L << Math.min(failureAttempt++, 5));
    }

    private static String formatDelay(long millis) {
        long seconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(millis));
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
    }

    private static long millisUntilQuotaReset() {
        ZonedDateTime now = ZonedDateTime.now(YOUTUBE_QUOTA_ZONE);
        ZonedDateTime reset = now.toLocalDate().plusDays(1).atStartOfDay(YOUTUBE_QUOTA_ZONE).plusMinutes(2);
        return Math.max(60_000, Duration.between(now, reset).toMillis());
    }

    private static String quotaResetLabel() {
        ZonedDateTime reset = ZonedDateTime.now(YOUTUBE_QUOTA_ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(YOUTUBE_QUOTA_ZONE).plusMinutes(2)
                .withZoneSameInstant(ZoneId.systemDefault());
        return "automatically at " + reset.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static ApiException apiError(HttpResponse<String> response, String operation) {
        try {
            JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
            String reason = "";
            JsonArray errors = error == null ? null : error.getAsJsonArray("errors");
            if (errors != null && !errors.isEmpty()) {
                reason = string(errors.get(0).getAsJsonObject(), "reason");
            }
            String message = string(error, "message").replaceAll("<[^>]+>", "").trim();
            return new ApiException(response.statusCode(), reason, operation, requestId(response),
                    "API " + response.statusCode() + (message.isBlank() ? "" : ": " + message), null);
        } catch (Exception ignored) {
            return new ApiException(response.statusCode(), "", operation, requestId(response),
                    "API request failed (" + response.statusCode() + ")", ignored);
        }
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue("x-goog-request-id")
                .or(() -> response.headers().firstValue("x-guploader-uploadid"))
                .orElse("");
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static final class AuthException extends Exception {
        private final String diagnostic;

        private AuthException(String message, String diagnostic) {
            super(message);
            this.diagnostic = diagnostic;
        }

        private AuthException(String message, String diagnostic, Throwable cause) {
            super(message, cause);
            this.diagnostic = diagnostic;
        }
    }

    private static final class ApiException extends Exception {
        private final int status;
        private final String reason;
        private final String operation;
        private final String requestId;

        private ApiException(int status, String reason, String operation, String requestId,
                             String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.operation = operation;
            this.requestId = requestId;
        }

        private String diagnostic() {
            return "Operation: " + operation
                    + "\nHTTP status: " + status
                    + "\nReason: " + (reason.isBlank() ? "not provided" : reason)
                    + "\nMessage: " + getMessage()
                    + (requestId.isBlank() ? "" : "\nGoogle request ID: " + requestId);
        }

        private boolean isQuotaExceeded() {
            return status == 403 && ("quotaExceeded".equals(reason)
                    || getMessage().toLowerCase().contains("quota"));
        }

        private boolean isRateLimited() {
            return status == 429 || "rateLimitExceeded".equals(reason);
        }

        private boolean isChatUnavailable() {
            return "liveChatEnded".equals(reason) || "liveChatDisabled".equals(reason)
                    || "liveChatNotFound".equals(reason);
        }
    }
}
