package de.tenkayro.chatcraft.twitch;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.command.CommandParser;
import de.tenkayro.chatcraft.command.CommandService;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.dashboard.ActivityLog;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TwitchConnection {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private final CommandService commands;
    private final ActionQueue queue;
    private final ActivityLog activity;
    private final TwitchChatFeed chatFeed;
    private final TwitchEventSub eventSub;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ChatCraft-Twitch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(true);
    private volatile State state = State.DISCONNECTED;
    private volatile String detail = "Disconnected";
    private volatile SSLSocket socket;
    private volatile ScheduledFuture<?> reconnectTask;
    private int reconnectAttempt;

    public TwitchConnection(CommandService commands, ActionQueue queue, ActivityLog activity, TwitchChatFeed chatFeed,
                            TwitchEventSub eventSub) {
        this.commands = commands;
        this.queue = queue;
        this.activity = activity;
        this.chatFeed = chatFeed;
        this.eventSub = eventSub;
    }

    public synchronized void connect() {
        if (state == State.CONNECTED || state == State.CONNECTING) return;
        cancelReconnect();
        ChatCraftConfig config = ChatCraftConfig.get();
        if (normalizeUsername(config.username).isEmpty()) {
            state = State.ERROR;
            detail = "Twitch username is not configured";
            return;
        }
        manualDisconnect.set(false);
        state = State.CONNECTING;
        detail = "Connecting";
        activity.add("system", "", "", "Twitch", "connecting");
        executor.execute(this::runConnection);
    }

    public synchronized void disconnect() {
        manualDisconnect.set(true);
        cancelReconnect();
        closeSocket();
        state = State.DISCONNECTED;
        detail = "Disconnected";
        reconnectAttempt = 0;
        eventSub.disconnect();
        queue.offerPriority(new ChatAction(ChatAction.Type.RELEASE_ALL));
        activity.add("system", "", "", "Twitch", "disconnected");
    }

    public State state() { return state; }
    public String detail() { return detail; }

    private void runConnection() {
        ChatCraftConfig config = ChatCraftConfig.get();
        String channel = normalizeUsername(config.username);
        String nick = channel;
        boolean anonymous = config.oauthToken.isBlank();
        if (anonymous) nick = "justinfan" + (10000 + (int) (Math.random() * 80000));
        try {
            SSLSocket active = (SSLSocket) SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697);
            socket = active;
            active.setSoTimeout(0);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(active.getOutputStream(), StandardCharsets.UTF_8));
            send(output, "PASS " + (anonymous ? "SCHMOOPIIE" : normalizeToken(config.oauthToken)));
            send(output, "NICK " + nick);
            send(output, "CAP REQ :twitch.tv/tags twitch.tv/commands");
            send(output, "JOIN #" + channel);
            state = State.CONNECTED;
            detail = "Connected to #" + channel;
            reconnectAttempt = 0;
            ChatCraftClient.LOGGER.info("Connected to Twitch channel #{}", channel);
            activity.add("system", "", "", "Twitch", "connected to #" + channel);
            if (!anonymous) eventSub.connect();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!manualDisconnect.get() && (line = input.readLine()) != null) {
                    if (line.startsWith("PING ")) {
                        send(output, "PONG " + line.substring(5));
                    } else {
                        handleLine(line);
                    }
                }
            }
            if (!manualDisconnect.get()) throw new IOException("Twitch closed the connection");
        } catch (Exception exception) {
            if (!manualDisconnect.get()) {
                state = State.ERROR;
                detail = "Connection lost: " + exception.getClass().getSimpleName();
                ChatCraftClient.LOGGER.warn("Twitch connection lost: {}", exception.toString());
                activity.add("system", "", "", "Twitch", detail);
            }
        } finally {
            closeSocket();
            queue.offerPriority(new ChatAction(ChatAction.Type.RELEASE_ALL));
            if (!manualDisconnect.get()) scheduleReconnect();
        }
    }

    private void handleLine(String line) {
        Map<String, String> tags = parseTags(line);
        if (line.contains(" USERNOTICE ")) {
            handleUserNotice(tags);
            return;
        }
        int marker = line.indexOf(" PRIVMSG ");
        int messageStart = line.indexOf(" :", marker + 9);
        if (marker < 0 || messageStart < 0) return;
        String user = "unknown";
        String userColor = "";
        if (line.startsWith("@")) {
            user = decodeTag(tags.getOrDefault("display-name", tags.getOrDefault("login", user)));
            userColor = tags.getOrDefault("color", "");
        }
        if (user.equals("unknown")) {
            int bang = line.indexOf('!');
            int colon = line.indexOf(':');
            if (colon >= 0 && bang > colon) user = line.substring(colon + 1, bang);
        }
        String message = CommandParser.sanitizeMessage(line.substring(messageStart + 2));
        activity.add("chat", user, message, "", "received");
        chatFeed.accept(user, message, parseColor(userColor));
        if (CommandService.isMinecraftCommand(message)) {
            commands.submitMinecraftCommand(user, message, isBroadcasterOrModerator(user, tags));
            return;
        }
        commands.submit(user, message, false);
    }

    private static boolean isBroadcasterOrModerator(String user, Map<String, String> tags) {
        String badges = tags.getOrDefault("badges", "");
        boolean broadcaster = badges.contains("broadcaster/")
                || normalizeUsername(user).equals(normalizeUsername(ChatCraftConfig.get().username));
        boolean moderator = "1".equals(tags.get("mod")) || badges.contains("moderator/");
        return broadcaster || moderator;
    }

    private void handleUserNotice(Map<String, String> tags) {
        String type = tags.getOrDefault("msg-id", "");
        if (!java.util.Set.of("sub", "resub", "subgift", "anonsubgift", "giftpaidupgrade",
                "anongiftpaidupgrade", "primepaidupgrade").contains(type)) return;
        String user = decodeTag(tags.getOrDefault("display-name",
                tags.getOrDefault("msg-param-recipient-display-name", "subscriber")));
        eventSub.enqueueSub(user, false, tags.getOrDefault("id", ""));
    }

    private static Map<String, String> parseTags(String line) {
        if (!line.startsWith("@")) return Map.of();
        int tagEnd = line.indexOf(' ');
        if (tagEnd < 1) return Map.of();
        return java.util.Arrays.stream(line.substring(1, tagEnd).split(";"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(p -> p[0],
                        p -> p.length == 2 ? p[1] : "", (a, b) -> b));
    }

    private static int parseColor(String value) {
        if (value != null && value.matches("#[0-9A-Fa-f]{6}")) {
            try {
                return Integer.parseInt(value.substring(1), 16);
            } catch (NumberFormatException ignored) { }
        }
        return 0x9146FF;
    }

    private static String decodeTag(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                decoded.append(switch (character) {
                    case 's' -> ' ';
                    case ':' -> ';';
                    case 'r' -> '\r';
                    case 'n' -> '\n';
                    default -> character;
                });
                escaped = false;
            } else {
                decoded.append(character);
            }
        }
        if (escaped) decoded.append('\\');
        return decoded.toString();
    }

    private void scheduleReconnect() {
        if (!ChatCraftConfig.get().autoReconnect) {
            state = State.DISCONNECTED;
            return;
        }
        long delay = Math.min(60, 2L << Math.min(reconnectAttempt++, 5));
        detail = "Reconnecting in " + delay + "s";
        activity.add("system", "", "", "Twitch", detail);
        reconnectTask = executor.schedule(() -> {
            synchronized (TwitchConnection.this) {
                reconnectTask = null;
                if (manualDisconnect.get()) return;
            }
            connect();
        }, delay, TimeUnit.SECONDS);
    }

    private synchronized void cancelReconnect() {
        ScheduledFuture<?> pending = reconnectTask;
        reconnectTask = null;
        if (pending != null) pending.cancel(false);
    }

    private synchronized void closeSocket() {
        SSLSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT).replaceFirst("^[@#]", "");
    }

    private static String normalizeToken(String token) {
        String value = token == null ? "" : token.trim();
        return value.regionMatches(true, 0, "oauth:", 0, 6) ? value : "oauth:" + value;
    }
}
