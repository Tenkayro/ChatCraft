package de.tenkayro.chatcraft.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives follow and subscription notifications that are not regular IRC chat messages. */
public final class TwitchEventSub {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, AUTH_REQUIRED, ERROR }
    private static final URI WEBSOCKET_URI = URI.create("wss://eventsub.wss.twitch.tv/ws");
    private static final URI VALIDATE_URI = URI.create("https://id.twitch.tv/oauth2/validate");
    private static final URI SUBSCRIPTIONS_URI = URI.create("https://api.twitch.tv/helix/eventsub/subscriptions");
    private static final int MAX_SEEN_MESSAGES = 512;

    private final ActionQueue queue;
    private final ActivityLog activity;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ChatCraft-EventSub");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final ArrayDeque<String> seenOrder = new ArrayDeque<>();
    private final Set<String> seenMessages = new HashSet<>();
    private volatile WebSocket socket;
    private volatile State state = State.DISCONNECTED;
    private volatile String detail = "Disconnected";

    public TwitchEventSub(ActionQueue queue, ActivityLog activity) {
        this.queue = queue;
        this.activity = activity;
    }

    public void connect() {
        ChatCraftConfig config = ChatCraftConfig.get();
        if (config.oauthToken.isBlank() || config.twitchClientId.isBlank()) return;
        disconnect();
        enabled.set(true);
        state = State.CONNECTING;
        detail = "Connecting follow events";
        open(WEBSOCKET_URI);
    }

    public void disconnect() {
        enabled.set(false);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect");
        state = State.DISCONNECTED;
        detail = "Disconnected";
    }

    public State state() { return state; }
    public String detail() { return detail; }

    public void testFollow() {
        enqueueFollow("TestFollower", true);
    }

    public void testSub() {
        enqueueSub("TestSubscriber", true);
    }

    private void open(URI uri) {
        if (!enabled.get()) return;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, new Listener())
                .whenComplete((webSocket, error) -> {
                    if (error != null && enabled.get()) {
                        state = State.ERROR;
                        detail = "Could not connect to Twitch EventSub";
                        activity.add("system", "", "", "EventSub", "connection failed");
                        ChatCraftClient.LOGGER.warn("Could not connect to Twitch EventSub", error);
                    } else if (webSocket != null) {
                        socket = webSocket;
                    }
                });
    }

    private void handle(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject metadata = root.getAsJsonObject("metadata");
            String messageType = string(metadata, "message_type");
            if ("session_welcome".equals(messageType)) {
                String sessionId = string(root.getAsJsonObject("payload").getAsJsonObject("session"), "id");
                executor.execute(() -> subscribe(sessionId));
                return;
            }
            if ("session_reconnect".equals(messageType)) {
                String url = string(root.getAsJsonObject("payload").getAsJsonObject("session"), "reconnect_url");
                if (!url.isBlank()) open(URI.create(url));
                return;
            }
            if (!"notification".equals(messageType)) return;
            String messageId = string(metadata, "message_id");
            if (!remember(messageId)) return;
            JsonObject payload = root.getAsJsonObject("payload");
            JsonObject subscription = payload.getAsJsonObject("subscription");
            JsonObject event = payload.getAsJsonObject("event");
            String type = string(subscription, "type");
            String user = string(event, "user_name");
            if (user.isBlank()) user = string(event, "user_login");
            if ("channel.follow".equals(type)) enqueueFollow(user, false);
        } catch (RuntimeException exception) {
            ChatCraftClient.LOGGER.warn("Could not read Twitch EventSub message", exception);
            activity.warn("EventSub", "Could not read a Twitch event: " + exception.getClass().getSimpleName());
        }
    }

    private void subscribe(String sessionId) {
        try {
            ChatCraftConfig config = ChatCraftConfig.get();
            HttpRequest validation = HttpRequest.newBuilder(VALIDATE_URI)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "OAuth " + config.oauthToken)
                    .GET().build();
            HttpResponse<String> validated = http.send(validation, HttpResponse.BodyHandlers.ofString());
            if (validated.statusCode() != 200) throw new IllegalStateException("token validation failed");
            JsonObject token = JsonParser.parseString(validated.body()).getAsJsonObject();
            String userId = string(token, "user_id");
            if (userId.isBlank()) throw new IllegalStateException("token has no Twitch user id");
            JsonArray scopes = token.has("scopes") && token.get("scopes").isJsonArray()
                    ? token.getAsJsonArray("scopes") : new JsonArray();
            boolean canReadFollowers = false;
            for (var scope : scopes) {
                if ("moderator:read:followers".equals(scope.getAsString())) canReadFollowers = true;
            }
            if (!canReadFollowers) {
                authorizationRequired("Follow permission is missing; re-authorize Twitch in the web panel");
                return;
            }
            createSubscription(config, sessionId, "channel.follow", "2", userId, true);
            state = State.CONNECTED;
            detail = "Follow events connected";
            activity.add("system", "", "", "EventSub", "follow events connected");
        } catch (AuthorizationException exception) {
            authorizationRequired("Twitch rejected the follow permission; re-authorize Twitch in the web panel");
        } catch (Exception exception) {
            stopAfterFailure(State.ERROR, "Follow events failed: " + exception.getClass().getSimpleName());
            activity.add("system", "", "", "EventSub", detail);
            ChatCraftClient.LOGGER.warn("Could not subscribe to Twitch EventSub", exception);
        }
    }

    private void createSubscription(ChatCraftConfig config, String sessionId, String type, String version,
                                    String userId, boolean follow) throws Exception {
        JsonObject condition = new JsonObject();
        condition.addProperty("broadcaster_user_id", userId);
        if (follow) condition.addProperty("moderator_user_id", userId);
        JsonObject transport = new JsonObject();
        transport.addProperty("method", "websocket");
        transport.addProperty("session_id", sessionId);
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("version", version);
        body.add("condition", condition);
        body.add("transport", transport);
        HttpRequest request = HttpRequest.newBuilder(SUBSCRIPTIONS_URI)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.oauthToken)
                .header("Client-Id", config.twitchClientId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new AuthorizationException();
        }
        if (response.statusCode() != 202) {
            throw new IllegalStateException(type + " returned " + response.statusCode() + ": " + response.body());
        }
    }

    private void authorizationRequired(String reason) {
        stopAfterFailure(State.AUTH_REQUIRED, reason);
        activity.add("system", "", "", "EventSub", reason);
        ChatCraftClient.LOGGER.warn("Twitch follow events disabled: {}", reason);
    }

    private void stopAfterFailure(State failedState, String reason) {
        enabled.set(false);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "subscription unavailable");
        state = failedState;
        detail = reason;
    }

    private synchronized boolean remember(String id) {
        if (id.isBlank()) return true;
        if (seenMessages.contains(id)) return false;
        seenMessages.add(id);
        seenOrder.addLast(id);
        while (seenOrder.size() > MAX_SEEN_MESSAGES) seenMessages.remove(seenOrder.removeFirst());
        return true;
    }

    private void enqueueFollow(String user, boolean test) {
        if (!ChatCraftConfig.get().followFireworkEnabled) return;
        queue.offer(new ChatAction(ChatAction.Type.FOLLOW_EVENT, user));
        activity.add("event", user, "follow", "Firework", test ? "test queued" : "queued");
    }

    public void enqueueSub(String user, boolean test) {
        enqueueSub(user, test, "");
    }

    public void enqueueSub(String user, boolean test, String messageId) {
        if (!test && !remember(messageId)) return;
        if (!ChatCraftConfig.get().subTntEnabled) return;
        queue.offer(new ChatAction(ChatAction.Type.SUB_EVENT, user));
        activity.add("event", user, "subscription", "TNT + firework", test ? "test queued" : "queued");
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String complete = message.toString();
                message.setLength(0);
                handle(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                socket = null;
                if (enabled.get()) executor.schedule(() -> open(WEBSOCKET_URI), 3, TimeUnit.SECONDS);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (enabled.get()) {
                ChatCraftClient.LOGGER.warn("Twitch EventSub WebSocket error", error);
                activity.error("EventSub", "WebSocket error: " + error.getClass().getSimpleName());
            }
        }
    }

    private static final class AuthorizationException extends Exception { }
}
