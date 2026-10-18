package de.tenkayro.chatcraft.twitch;

import de.tenkayro.chatcraft.command.CommandParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends Twitch messages through Minecraft's normal chat HUD and retains plain data for OBS. */
public final class TwitchChatFeed {
    private static final int MAX_MESSAGES = 100;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+(?:[.,][0-9]+){0,2}");
    private static final Identifier PLATFORM_ICONS_FONT = Identifier.of("chatcraft", "platform_icons");
    private static final String TWITCH_ICON = "\uE000";
    private static final String YOUTUBE_ICON = "\uE001";

    private final CommandParser parser;
    private final Deque<Message> messages = new ArrayDeque<>();

    public TwitchChatFeed(CommandParser parser) {
        this.parser = parser;
    }

    public void accept(String user, String message, int color) {
        accept(user, message, color, "Twitch");
    }

    public void accept(String user, String message, int color, String source) {
        synchronized (messages) {
            messages.addLast(new Message(System.currentTimeMillis(), user, message, color, source));
            while (messages.size() > MAX_MESSAGES) messages.removeFirst();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.inGameHud != null) client.inGameHud.getChatHud().addMessage(format(user, message, color, source));
        });
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
                item.put("source", message.source);
                result.add(item);
            }
        }
        return result;
    }

    public void close() {
        synchronized (messages) {
            messages.clear();
        }
    }

    private Text format(String user, String message, int color, String source) {
        String icon = source.equals("YouTube") ? YOUTUBE_ICON : TWITCH_ICON;
        MutableText line = Text.literal(icon).styled(style -> style.withFont(PLATFORM_ICONS_FONT));
        line.append(Text.literal(" "));
        line.append(Text.literal(user).styled(style -> style.withColor(color)));
        line.append(Text.literal(" \u00BB ").formatted(Formatting.DARK_GRAY));
        Matcher matcher = WORD.matcher(message);
        int position = 0;
        while (matcher.find()) {
            if (matcher.start() > position) {
                line.append(Text.literal(message.substring(position, matcher.start())).formatted(Formatting.GRAY));
            }
            String word = matcher.group();
            line.append(Text.literal(word).formatted(
                    parser.isCommandToken(word) || word.equalsIgnoreCase("cmd")
                            ? Formatting.WHITE : Formatting.GRAY));
            position = matcher.end();
        }
        if (position < message.length()) {
            line.append(Text.literal(message.substring(position)).formatted(Formatting.GRAY));
        }
        return line;
    }

    private record Message(long timestamp, String user, String text, int color, String source) { }
}
