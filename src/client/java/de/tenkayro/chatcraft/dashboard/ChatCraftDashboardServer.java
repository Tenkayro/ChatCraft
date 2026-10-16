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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatCraftDashboardServer {
    public static final int PORT = 38473;
    public static final String BASE_URL = "https://localhost:" + PORT;
    public static final String REDIRECT_URL = BASE_URL + "/oauth/callback";
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final long OAUTH_LIFETIME_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ActivityLog activity;
    private final String sessionNonce = randomHex(24);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile HttpsServer server;
    private volatile ExecutorService executor;
    private volatile String oauthState = "";
    private volatile long oauthExpiresAt;
    private volatile String detail = "Not started";

    public ChatCraftDashboardServer(ActivityLog activity) {
        this.activity = activity;
    }

    public synchronized void start() {
        if (server != null) return;
        try {
            SSLContext sslContext = LocalTlsCertificate.createContext();
            HttpsServer active = HttpsServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
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
            detail = "Running on " + BASE_URL;
            activity.add("system", "", "", "Dashboard", detail);
            ChatCraftClient.LOGGER.info("ChatCraft dashboard started at {}", BASE_URL);
        } catch (Exception exception) {
            detail = "Could not start: " + exception.getClass().getSimpleName();
            activity.add("system", "", "", "Dashboard", detail);
            ChatCraftClient.LOGGER.error("Could not start ChatCraft dashboard on port {}", PORT, exception);
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

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                sendText(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/oauth/callback" -> serveDashboard(exchange);
                case "/app.js" -> serveResource(exchange, "/assets/chatcraft/dashboard/app.js",
                    "text/javascript; charset=utf-8");
                case "/style.css" -> serveResource(exchange, "/assets/chatcraft/dashboard/style.css",
                    "text/css; charset=utf-8");
                case "/api/status" -> status(exchange);
                case "/api/oauth/start" -> startOauth(exchange);
                case "/api/oauth/token" -> saveOauthToken(exchange);
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
        payload.put("redirectUrl", REDIRECT_URL);
        payload.put("events", activity.snapshot());
        sendJson(exchange, 200, payload);
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
            + "&redirect_uri=" + encode(REDIRECT_URL)
            + "&scope=" + encode("chat:read")
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
        boolean canReadChat = false;
        for (var scope : scopes) if ("chat:read".equals(scope.getAsString())) canReadChat = true;
        if (login.isBlank() || !canReadChat || !Objects.equals(clientId, ChatCraftConfig.get().twitchClientId)) {
            throw new IOException("Token login, Client ID, or chat:read scope is invalid");
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
        headers.set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; img-src 'self'; "
            + "style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action https://id.twitch.tv");
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
