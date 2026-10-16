package de.tenkayro.chatcraft.twitch;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.action.ActionQueue;
import de.tenkayro.chatcraft.action.ChatAction;
import de.tenkayro.chatcraft.command.CommandService;
import de.tenkayro.chatcraft.config.ChatCraftConfig;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TwitchConnection {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private final CommandService commands;
    private final ActionQueue queue;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ChatCraft-Twitch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(true);
    private volatile State state = State.DISCONNECTED;
    private volatile String detail = "Disconnected";
    private volatile SSLSocket socket;
    private int reconnectAttempt;

    public TwitchConnection(CommandService commands, ActionQueue queue) {
        this.commands = commands;
        this.queue = queue;
    }

    public synchronized void connect() {
        if (state == State.CONNECTED || state == State.CONNECTING) return;
        ChatCraftConfig config = ChatCraftConfig.get();
        if (normalizeUsername(config.username).isEmpty()) {
            state = State.ERROR;
            detail = "Twitch username is not configured";
            return;
        }
        manualDisconnect.set(false);
        state = State.CONNECTING;
        detail = "Connecting";
        executor.execute(this::runConnection);
    }

    public synchronized void disconnect() {
        manualDisconnect.set(true);
        closeSocket();
        state = State.DISCONNECTED;
        detail = "Disconnected";
        reconnectAttempt = 0;
        queue.offerPriority(new ChatAction(ChatAction.Type.RELEASE_ALL));
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
            }
        } finally {
            closeSocket();
            queue.offerPriority(new ChatAction(ChatAction.Type.RELEASE_ALL));
            if (!manualDisconnect.get()) scheduleReconnect();
        }
    }

    private void handleLine(String line) {
        int marker = line.indexOf(" PRIVMSG ");
        int messageStart = line.indexOf(" :", marker + 9);
        if (marker < 0 || messageStart < 0) return;
        String user = "unknown";
        if (line.startsWith("@")) {
            int tagEnd = line.indexOf(' ');
            Map<String, String> tags = java.util.Arrays.stream(line.substring(1, tagEnd).split(";"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(p -> p[0], p -> p.length == 2 ? p[1] : "", (a, b) -> b));
            user = tags.getOrDefault("display-name", tags.getOrDefault("login", user));
        }
        if (user.equals("unknown")) {
            int bang = line.indexOf('!');
            int colon = line.indexOf(':');
            if (colon >= 0 && bang > colon) user = line.substring(colon + 1, bang);
        }
        commands.submit(user, line.substring(messageStart + 2), false);
    }

    private void scheduleReconnect() {
        if (!ChatCraftConfig.get().autoReconnect) {
            state = State.DISCONNECTED;
            return;
        }
        long delay = Math.min(60, 2L << Math.min(reconnectAttempt++, 5));
        detail = "Reconnecting in " + delay + "s";
        executor.schedule(this::connect, delay, TimeUnit.SECONDS);
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
