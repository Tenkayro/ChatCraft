package de.tenkayro.chatcraft.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retrieves low-frequency channel metrics without blocking Minecraft's render thread. */
public final class PlatformStatsService implements AutoCloseable {
    private static final long TWITCH_FOLLOWERS_REFRESH_MILLIS = 60_000L;
    private static final long YOUTUBE_SUBSCRIBERS_REFRESH_MILLIS = 10 * 60_000L;
    private static final long VIEWERS_REFRESH_MILLIS = 60_000L;
    private static final long ERROR_RETRY_MILLIS = 2 * 60_000L;
    private static final String TWITCH_API = "https://api.twitch.tv/helix/";
    private static final String YOUTUBE_API = "https://www.googleapis.com/youtube/v3/";

    private final ActivityLog activity;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ChatCraft-Platform-Stats");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private volatile Long twitchFollowers;
    private volatile Long twitchViewers;
    private volatile Long youtubeSubscribers;
    private volatile Long youtubeViewers;
    private volatile long nextTwitchFollowers;
    private volatile long nextTwitchViewers;
    private volatile long nextYoutubeSubscribers;
    private volatile long nextYoutubeViewers;
    private volatile String twitchIdentityKey = "";
    private volatile String twitchUserId = "";
    private volatile String lastTwitchError = "";
    private volatile String lastYoutubeError = "";
    private String configurationKey = "";
    private String activeYoutubeBroadcast = "";
    private int ticks;

    public PlatformStatsService(ActivityLog activity) {
        this.activity = activity;
    }

    public Long twitchFollowers() { return twitchFollowers; }
    public Long twitchViewers() { return twitchViewers; }
    public Long youtubeSubscribers() { return youtubeSubscribers; }
    public Long youtubeViewers() { return youtubeViewers; }

    public void requestRefresh() {
        nextTwitchFollowers = 0;
        nextTwitchViewers = 0;
        nextYoutubeSubscribers = 0;
        nextYoutubeViewers = 0;
    }

    public void tick(MinecraftClient client) {
        if (++ticks < 20) return;
        ticks = 0;
        ChatCraftConfig config = ChatCraftConfig.get();
        String currentConfigurationKey = config.username + '\n' + config.twitchClientId + '\n'
                + config.oauthToken.hashCode() + '\n' + config.youtubeClientId + '\n'
                + config.youtubeRefreshToken.hashCode();
        if (!currentConfigurationKey.equals(configurationKey)) {
            configurationKey = currentConfigurationKey;
            twitchIdentityKey = "";
            requestRefresh();
        }
        String currentBroadcast = ChatCraftClient.youtube().activeBroadcastId();
        if (!currentBroadcast.equals(activeYoutubeBroadcast)) {
            activeYoutubeBroadcast = currentBroadcast;
            nextYoutubeViewers = 0;
        }
        long now = System.currentTimeMillis();
        if (!anyEnabled(config) || !refreshDue(config, now)
                || !refreshRunning.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    refresh(config, now);
                } finally {
                    refreshRunning.set(false);
                }
            });
        } catch (RuntimeException exception) {
            refreshRunning.set(false);
            if (!executor.isShutdown()) reportError("Stats", "Could not schedule platform stats", exception);
        }
    }

    private void refresh(ChatCraftConfig config, long now) {
        refreshTwitch(config, now);
        refreshYoutube(config, now);
    }

    private void refreshTwitch(ChatCraftConfig config, long now) {
        boolean followersDue = config.statsTwitchFollowers && now >= nextTwitchFollowers;
        boolean viewersDue = config.statsTwitchViewers && now >= nextTwitchViewers;
        if (!followersDue && !viewersDue) return;
        if (config.twitchClientId.isBlank() || config.oauthToken.isBlank() || config.username.isBlank()) {
            twitchFollowers = null;
            twitchViewers = null;
            delayTwitch(now, followersDue, viewersDue, ERROR_RETRY_MILLIS);
            reportConfigurationIssue("Twitch", "Twitch stats need a channel, Client ID, and OAuth token");
            return;
        }

        try {
            String token = normalizeTwitchToken(config.oauthToken);
            String identityKey = config.twitchClientId + '\n' + config.username + '\n' + token.hashCode();
            if (!Objects.equals(identityKey, twitchIdentityKey) || twitchUserId.isBlank()) {
                JsonObject users = twitchGet("users?login=" + encode(config.username), config, token);
                JsonArray data = users.getAsJsonArray("data");
                if (data == null || data.isEmpty()) throw new IllegalStateException("Twitch channel was not found");
                twitchUserId = string(data.get(0).getAsJsonObject(), "id");
                twitchIdentityKey = identityKey;
            }
            if (followersDue) {
                JsonObject followers = twitchGet("channels/followers?broadcaster_id=" + encode(twitchUserId)
                        + "&first=1", config, token);
                twitchFollowers = followers.has("total") ? followers.get("total").getAsLong() : null;
                nextTwitchFollowers = now + TWITCH_FOLLOWERS_REFRESH_MILLIS;
            }
            if (viewersDue) {
                JsonObject streams = twitchGet("streams?user_id=" + encode(twitchUserId) + "&first=1", config, token);
                JsonArray data = streams.getAsJsonArray("data");
                twitchViewers = data == null || data.isEmpty()
                        ? 0L : longValue(data.get(0).getAsJsonObject(), "viewer_count");
                nextTwitchViewers = now + VIEWERS_REFRESH_MILLIS;
            }
            lastTwitchError = "";
        } catch (Exception exception) {
            delayTwitch(now, followersDue, viewersDue, ERROR_RETRY_MILLIS);
            reportPlatformError("Twitch", exception);
        }
    }

    private void refreshYoutube(ChatCraftConfig config, long now) {
        boolean subscribersDue = config.statsYoutubeSubscribers && now >= nextYoutubeSubscribers;
        boolean viewersDue = config.statsYoutubeViewers && now >= nextYoutubeViewers;
        if (!subscribersDue && !viewersDue) return;
        if (config.youtubeRefreshToken.isBlank()) {
            youtubeSubscribers = null;
            youtubeViewers = null;
            delayYoutube(now, subscribersDue, viewersDue, ERROR_RETRY_MILLIS);
            reportConfigurationIssue("YouTube", "YouTube stats need an authorized YouTube account");
            return;
        }

        try {
            String token = ChatCraftClient.youtube().accessTokenForApi();
            if (subscribersDue) {
                JsonObject channels = youtubeGet("channels?part=statistics&mine=true&maxResults=1", token);
                JsonArray items = channels.getAsJsonArray("items");
                if (items == null || items.isEmpty()) throw new IllegalStateException("YouTube channel was not found");
                JsonObject statistics = items.get(0).getAsJsonObject().getAsJsonObject("statistics");
                youtubeSubscribers = longValue(statistics, "subscriberCount");
                nextYoutubeSubscribers = now + YOUTUBE_SUBSCRIBERS_REFRESH_MILLIS;
            }
            if (viewersDue) {
                String broadcastId = ChatCraftClient.youtube().activeBroadcastId();
                if (broadcastId.isBlank()) {
                    youtubeViewers = ChatCraftClient.youtube().state()
                            == de.tenkayro.chatcraft.youtube.YouTubeConnection.State.WAITING ? 0L : null;
                } else {
                    JsonObject videos = youtubeGet("videos?part=liveStreamingDetails&id="
                            + encode(broadcastId) + "&maxResults=1", token);
                    JsonArray items = videos.getAsJsonArray("items");
                    JsonObject details = items == null || items.isEmpty() ? null
                            : items.get(0).getAsJsonObject().getAsJsonObject("liveStreamingDetails");
                    youtubeViewers = details != null && details.has("concurrentViewers")
                            ? details.get("concurrentViewers").getAsLong() : null;
                }
                nextYoutubeViewers = now + VIEWERS_REFRESH_MILLIS;
            }
            lastYoutubeError = "";
        } catch (HttpStatusException exception) {
            long retry = exception.status == 403 ? 60 * 60_000L : ERROR_RETRY_MILLIS;
            delayYoutube(now, subscribersDue, viewersDue, retry);
            reportPlatformError("YouTube", exception);
        } catch (Exception exception) {
            delayYoutube(now, subscribersDue, viewersDue, ERROR_RETRY_MILLIS);
            reportPlatformError("YouTube", exception);
        }
    }

    private JsonObject twitchGet(String path, ChatCraftConfig config, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TWITCH_API + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", config.twitchClientId).GET().build();
        return send(request);
    }

    private JsonObject youtubeGet(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(YOUTUBE_API + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).GET().build();
        return send(request);
    }

    private JsonObject send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body().replace('\r', ' ').replace('\n', ' ').trim();
            if (body.length() > 1_000) body = body.substring(0, 1_000) + "…";
            throw new HttpStatusException(response.statusCode(), "API returned HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : "\nResponse: " + body));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void delayTwitch(long now, boolean followers, boolean viewers, long delay) {
        if (followers) nextTwitchFollowers = now + delay;
        if (viewers) nextTwitchViewers = now + delay;
    }

    private void delayYoutube(long now, boolean subscribers, boolean viewers, long delay) {
        if (subscribers) nextYoutubeSubscribers = now + delay;
        if (viewers) nextYoutubeViewers = now + delay;
    }

    private void reportPlatformError(String platform, Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if ("Twitch".equals(platform)) {
            if (message.equals(lastTwitchError)) return;
            lastTwitchError = message;
        } else {
            if (message.equals(lastYoutubeError)) return;
            lastYoutubeError = message;
        }
        reportError(platform + " stats", "Could not refresh platform statistics", exception);
    }

    private void reportConfigurationIssue(String platform, String message) {
        if ("Twitch".equals(platform)) {
            if (message.equals(lastTwitchError)) return;
            lastTwitchError = message;
        } else {
            if (message.equals(lastYoutubeError)) return;
            lastYoutubeError = message;
        }
        activity.warn(platform + " stats", message);
    }

    private void reportError(String source, String message, Exception exception) {
        ChatCraftClient.LOGGER.warn("{}: {}", source, message, exception);
        activity.warn(source, message + ": " + exception.getMessage(), exception);
    }

    private static boolean anyEnabled(ChatCraftConfig config) {
        return config.statsTwitchFollowers || config.statsTwitchViewers
                || config.statsYoutubeSubscribers || config.statsYoutubeViewers;
    }

    private boolean refreshDue(ChatCraftConfig config, long now) {
        return config.statsTwitchFollowers && now >= nextTwitchFollowers
                || config.statsTwitchViewers && now >= nextTwitchViewers
                || config.statsYoutubeSubscribers && now >= nextYoutubeSubscribers
                || config.statsYoutubeViewers && now >= nextYoutubeViewers;
    }

    private static String normalizeTwitchToken(String token) {
        String value = token.trim();
        return value.regionMatches(true, 0, "oauth:", 0, 6) ? value.substring(6) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsString() : "";
    }

    private static long longValue(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsLong() : 0L;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class HttpStatusException extends Exception {
        private final int status;

        private HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
