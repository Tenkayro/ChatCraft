package de.tenkayro.chatcraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.minecraft.client.MinecraftClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatCraftDashboardServer {
    public static final int DEFAULT_PORT = 38473;
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final long OAUTH_LIFETIME_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ActivityLog activity;
    private final String sessionNonce = randomHex(24);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile HttpsServer server;
    private volatile ExecutorService executor;
    private volatile int activePort;
    private volatile InetAddress lanAddress;
    private volatile String oauthState = "";
    private volatile long oauthExpiresAt;
    private volatile String detail = "Not started";

    public ChatCraftDashboardServer(ActivityLog activity) {
        this.activity = activity;
    }

    public synchronized void start() {
        if (server != null) return;
        int port = ChatCraftConfig.get().dashboardPort;
        activePort = port;
        try {
            lanAddress = findLanAddress();
            SSLContext sslContext = LocalTlsCertificate.createContext(lanAddress);
            HttpsServer active = HttpsServer.create(new InetSocketAddress(port), 0);
            active.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters parameters) {
                    SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                    sslParameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    parameters.setSSLParameters(sslParameters);
                }
            });
            active.createContext("/", this::handle);
            ExecutorService threads = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "ChatCraft-Dashboard");
                thread.setDaemon(true);
                return thread;
            });
            active.setExecutor(threads);
            active.start();
            executor = threads;
            server = active;
            detail = "Running on " + baseUrl();
            activity.add("system", "", "", "Dashboard", detail);
            ChatCraftClient.LOGGER.info("ChatCraft dashboard started at {} (LAN: {})", baseUrl(), lanUrl());
        } catch (Exception exception) {
            detail = "Could not start: " + exception.getClass().getSimpleName();
            activity.add("system", "", "", "Dashboard", detail);
            ChatCraftClient.LOGGER.error("Could not start ChatCraft dashboard on port {}", port, exception);
        }
    }

    public synchronized void stop() {
        HttpsServer active = server;
        server = null;
        if (active != null) active.stop(0);
        ExecutorService threads = executor;
        executor = null;
        if (threads != null) threads.shutdownNow();
        detail = "Stopped";
    }

    public boolean running() { return server != null; }
    public String detail() { return detail; }
    public String baseUrl() { return "https://localhost:" + activePort; }
    public String lanUrl() {
        InetAddress address = lanAddress;
        return address == null ? "" : "https://" + address.getHostAddress() + ":" + activePort;
    }
    public String redirectUrl() { return baseUrl() + "/oauth/callback"; }

    public synchronized void restart() {
        stop();
        oauthState = "";
        oauthExpiresAt = 0;
        start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!allowedClient(exchange.getRemoteAddress().getAddress())) {
                sendText(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/oauth/callback" -> serveDashboard(exchange);
                case "/obs" -> serveResource(exchange, "/assets/chatcraft/dashboard/obs.html", "text/html; charset=utf-8");
                case "/obs.js" -> serveResource(exchange, "/assets/chatcraft/dashboard/obs.js", "text/javascript; charset=utf-8");
                case "/obs.css" -> serveResource(exchange, "/assets/chatcraft/dashboard/obs.css", "text/css; charset=utf-8");
                case "/app.js" -> serveResource(exchange, "/assets/chatcraft/dashboard/app.js",
                    "text/javascript; charset=utf-8");
                case "/style.css" -> serveResource(exchange, "/assets/chatcraft/dashboard/style.css",
                    "text/css; charset=utf-8");
                case "/api/status" -> status(exchange);
                case "/api/oauth/start" -> startOauth(exchange);
                case "/api/oauth/token" -> saveOauthToken(exchange);
                case "/api/settings" -> saveSettings(exchange);
                case "/api/gamerule" -> applyGameRule(exchange);
                case "/api/event/test" -> testEvent(exchange);
                case "/api/token/clear" -> clearToken(exchange);
                case "/api/control" -> control(exchange);
                default -> sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            }
        } catch (Exception exception) {
            ChatCraftClient.LOGGER.warn("Dashboard request failed: {}", exception.toString());
            if (exchange.getResponseCode() == -1) sendJson(exchange, 500, Map.of("error", "Request failed"));
        } finally {
            exchange.close();
        }
    }

    private void serveDashboard(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        String html = readResource("/assets/chatcraft/dashboard/index.html")
            .replace("__CHATCRAFT_SESSION__", sessionNonce);
        sendText(exchange, 200, html, "text/html; charset=utf-8");
    }

    private void serveResource(HttpExchange exchange, String path, String contentType) throws IOException {
        if (!method(exchange, "GET")) return;
        sendText(exchange, 200, readResource(path), contentType);
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        ChatCraftConfig config = ChatCraftConfig.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("twitchState", ChatCraftClient.twitch().state().name());
        payload.put("twitchDetail", ChatCraftClient.twitch().detail());
        payload.put("queueSize", ChatCraftClient.queue().size());
        payload.put("username", config.username);
        payload.put("controlsEnabled", config.controlsEnabled);
        payload.put("clientId", config.twitchClientId);
        payload.put("tokenConfigured", !config.oauthToken.isBlank());
        payload.put("redirectUrl", redirectUrl());
        payload.put("lanUrl", lanUrl());
        payload.put("obsUrl", (lanUrl().isBlank() ? baseUrl() : lanUrl()) + "/obs");
        payload.put("settings", settingsPayload(config));
        payload.put("events", activity.snapshot());
        payload.put("chat", ChatCraftClient.chatFeed().webMessages());
        payload.put("emotes", ChatCraftClient.chatFeed().webEmotes());
        sendJson(exchange, 200, payload);
    }

    private static boolean allowedClient(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }

    private static InetAddress findLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address;
                }
            }
        } catch (SocketException exception) {
            ChatCraftClient.LOGGER.warn("Could not determine the LAN address", exception);
        }
        return null;
    }

    private void saveSettings(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        JsonObject request = readJson(exchange);
        ChatCraftConfig config = ChatCraftConfig.get();
        synchronized (config) {
            config.username = string(request, "username");
            config.commandPrefix = string(request, "commandPrefix");
            config.controlsEnabled = bool(request, "controlsEnabled", config.controlsEnabled);
            config.overlayEnabled = bool(request, "overlayEnabled", config.overlayEnabled);
            config.autoConnect = bool(request, "autoConnect", config.autoConnect);
            config.autoReconnect = bool(request, "autoReconnect", config.autoReconnect);
            config.keepRunningUnfocused = bool(request, "keepRunningUnfocused", config.keepRunningUnfocused);
            config.followMobEnabled = bool(request, "followMobEnabled", config.followMobEnabled);
            config.subTntEnabled = bool(request, "subTntEnabled", config.subTntEnabled);
            config.privilegedCommandsEnabled = bool(request, "privilegedCommandsEnabled", config.privilegedCommandsEnabled);
            config.movementDurationTicks = integer(request, "movementDurationTicks", config.movementDurationTicks);
            config.cameraStepDegrees = decimal(request, "cameraStepDegrees", config.cameraStepDegrees);
            config.whitelist = strings(request, "whitelist");
            config.blacklist = strings(request, "blacklist");
            config.save();
        }
        activity.add("system", "", "", "Settings", "saved from dashboard");
        sendJson(exchange, 200, Map.of("ok", true, "settings", settingsPayload(config)));
    }

    private void clearToken(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        ChatCraftClient.twitch().disconnect();
        ChatCraftConfig config = ChatCraftConfig.get();
        synchronized (config) {
            config.oauthToken = "";
            config.save();
        }
        activity.add("system", "", "", "OAuth", "access token cleared");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void startOauth(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        JsonObject request = readJson(exchange);
        String clientId = string(request, "clientId").trim();
        if (!clientId.matches("[A-Za-z0-9]{5,100}")) {
            sendJson(exchange, 400, Map.of("error", "Enter a valid Twitch Client ID"));
            return;
        }
        oauthState = randomHex(24);
        oauthExpiresAt = System.currentTimeMillis() + OAUTH_LIFETIME_MILLIS;
        ChatCraftConfig config = ChatCraftConfig.get();
        synchronized (config) {
            config.twitchClientId = clientId;
            config.save();
        }
        String url = "https://id.twitch.tv/oauth2/authorize?response_type=token"
            + "&client_id=" + encode(clientId)
            + "&redirect_uri=" + encode(redirectUrl())
            + "&scope=" + encode("chat:read moderator:read:followers")
            + "&state=" + encode(oauthState);
        sendJson(exchange, 200, Map.of("url", url, "state", oauthState));
    }

    private void saveOauthToken(HttpExchange exchange) throws IOException, InterruptedException {
        if (!authorizedPost(exchange)) return;
        JsonObject request = readJson(exchange);
        String token = string(request, "token").trim();
        String state = string(request, "state");
        if (token.isBlank() || token.length() > 512 || !validState(state)) {
            sendJson(exchange, 400, Map.of("error", "OAuth response is invalid or expired"));
            return;
        }
        TokenDetails validated;
        try {
            validated = validateToken(token);
        } catch (IOException exception) {
            sendJson(exchange, 400, Map.of("error", exception.getMessage()));
            return;
        }
        ChatCraftConfig config = ChatCraftConfig.get();
        synchronized (config) {
            config.oauthToken = token;
            config.username = validated.login();
            config.save();
        }
        oauthState = "";
        oauthExpiresAt = 0;
        activity.add("system", "", "", "OAuth", "token saved for " + validated.login());
        sendJson(exchange, 200, Map.of("ok", true, "username", validated.login(),
            "expiresIn", validated.expiresIn()));
    }

    private void control(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        String action = string(readJson(exchange), "action");
        switch (action) {
            case "connect" -> ChatCraftClient.twitch().connect();
            case "disconnect" -> ChatCraftClient.twitch().disconnect();
            default -> {
                sendJson(exchange, 400, Map.of("error", "Unknown control action"));
                return;
            }
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void applyGameRule(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        JsonObject request = readJson(exchange);
        String rule = string(request, "rule");
        if (!rule.equals("keepInventory") && !rule.equals("doImmediateRespawn")) {
            sendJson(exchange, 400, Map.of("error", "Unsupported game rule"));
            return;
        }
        boolean value = bool(request, "value", false);
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null || client.player.networkHandler == null) {
                activity.add("system", "", "", "Game rule", "failed: no world is open");
                return;
            }
            client.player.networkHandler.sendChatCommand("gamerule " + rule + " " + value);
            activity.add("system", "", "", "Game rule", rule + " = " + value + " requested");
        });
        sendJson(exchange, 200, Map.of("ok", true, "rule", rule, "value", value));
    }

    private void testEvent(HttpExchange exchange) throws IOException {
        if (!authorizedPost(exchange)) return;
        String event = string(readJson(exchange), "event");
        ChatCraftConfig config = ChatCraftConfig.get();
        if ((event.equals("follow") && !config.followMobEnabled)
                || (event.equals("sub") && !config.subTntEnabled)) {
            sendJson(exchange, 409, Map.of("error", "Enable this event in Settings first"));
            return;
        }
        switch (event) {
            case "follow" -> ChatCraftClient.eventSub().testFollow();
            case "sub" -> ChatCraftClient.eventSub().testSub();
            default -> {
                sendJson(exchange, 400, Map.of("error", "Unknown event type"));
                return;
            }
        }
        sendJson(exchange, 200, Map.of("ok", true, "event", event));
    }

    private TokenDetails validateToken(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/validate"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "OAuth " + token)
            .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Twitch rejected the access token");
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String login = string(json, "login").trim().toLowerCase(java.util.Locale.ROOT);
        String clientId = string(json, "client_id");
        JsonArray scopes = json.has("scopes") && json.get("scopes").isJsonArray()
            ? json.getAsJsonArray("scopes") : new JsonArray();
        java.util.Set<String> granted = new java.util.HashSet<>();
        for (var scope : scopes) granted.add(scope.getAsString());
        boolean requiredScopes = granted.contains("chat:read")
                && granted.contains("moderator:read:followers");
        if (login.isBlank() || !requiredScopes || !Objects.equals(clientId, ChatCraftConfig.get().twitchClientId)) {
            throw new IOException("Token login, Client ID, or required Twitch permissions are invalid");
        }
        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 0;
        return new TokenDetails(login, expiresIn);
    }

    private boolean validState(String state) {
        return System.currentTimeMillis() <= oauthExpiresAt && !oauthState.isBlank()
            && constantTimeEquals(oauthState, state);
    }

    private boolean authorizedPost(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return false;
        String supplied = exchange.getRequestHeaders().getFirst("X-ChatCraft-Session");
        if (!constantTimeEquals(sessionNonce, supplied)) {
            sendJson(exchange, 403, Map.of("error", "Invalid dashboard session"));
            return false;
        }
        return true;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IOException("Request body is too large");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try { return object.get(key).getAsInt(); } catch (Exception ignored) { return fallback; }
    }

    private static float decimal(JsonObject object, String key, float fallback) {
        try { return object.get(key).getAsFloat(); } catch (Exception ignored) { return fallback; }
    }

    private static java.util.List<String> strings(JsonObject object, String key) {
        java.util.List<String> values = new java.util.ArrayList<>();
        if (!object.has(key) || !object.get(key).isJsonArray()) return values;
        for (var element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) values.add(value);
            }
        }
        return values;
    }

    private static Map<String, Object> settingsPayload(ChatCraftConfig config) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("username", config.username);
        settings.put("commandPrefix", config.commandPrefix);
        settings.put("controlsEnabled", config.controlsEnabled);
        settings.put("overlayEnabled", config.overlayEnabled);
        settings.put("autoConnect", config.autoConnect);
        settings.put("autoReconnect", config.autoReconnect);
        settings.put("keepRunningUnfocused", config.keepRunningUnfocused);
        settings.put("followMobEnabled", config.followMobEnabled);
        settings.put("subTntEnabled", config.subTntEnabled);
        settings.put("privilegedCommandsEnabled", config.privilegedCommandsEnabled);
        settings.put("movementDurationTicks", config.movementDurationTicks);
        settings.put("cameraStepDegrees", config.cameraStepDegrees);
        settings.put("whitelist", config.whitelist);
        settings.put("blacklist", config.blacklist);
        return settings;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
        return false;
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendText(exchange, status, GSON.toJson(value), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String value, String contentType) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; img-src 'self' https://cdn.7tv.app https://static-cdn.jtvnw.net; "
            + "style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; form-action https://id.twitch.tv");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = ChatCraftDashboardServer.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing dashboard resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8));
    }

    private record TokenDetails(String login, long expiresIn) { }
}
