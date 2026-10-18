package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.ChatCraftClient;
import de.tenkayro.chatcraft.config.ChatCraftConfig;
import de.tenkayro.chatcraft.stats.PlatformStatsService;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.stat.Stats;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A compact HUD backed exclusively by Minecraft's synchronized world statistics. */
public final class StatsOverlayRenderer {
    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 11;
    private static final int ACCENT = 0xFFE04B4B;
    private static final int BACKGROUND = 0xB814171C;
    private static final int LABEL_COLOR = 0xFF9DA3AE;
    private static final int VALUE_COLOR = 0xFFF4F5F7;
    private static final Identifier PLATFORM_ICONS_FONT = Identifier.of("chatcraft", "platform_icons");
    private static final String TWITCH_ICON = "\uE000";
    private static final String YOUTUBE_ICON = "\uE001";
    private static final int STAT_SYNC_INTERVAL_TICKS = 100;
    private static ClientPlayNetworkHandler lastNetworkHandler;
    private static int syncCountdown;

    private StatsOverlayRenderer() { }

    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
    }

    public static void tick(MinecraftClient client) {
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (client.player == null || networkHandler == null) {
            lastNetworkHandler = null;
            syncCountdown = 0;
            return;
        }
        if (!hasWorldStatsEnabled(ChatCraftConfig.get())) return;
        if (networkHandler != lastNetworkHandler || syncCountdown-- <= 0) {
            lastNetworkHandler = networkHandler;
            syncCountdown = STAT_SYNC_INTERVAL_TICKS;
            networkHandler.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        }
    }

    public static void requestWorldStatsRefresh() {
        syncCountdown = 0;
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden) return;

        ChatCraftConfig config = ChatCraftConfig.get();
        List<StatRow> rows = collectRows(player, config);
        if (rows.isEmpty()) return;

        boolean showTitle = config.statsTitleVisible && !config.statsTitle.isBlank();
        int contentWidth = showTitle ? client.textRenderer.getWidth(config.statsTitle) : 0;
        for (StatRow row : rows) {
            contentWidth = Math.max(contentWidth,
                    client.textRenderer.getWidth(row.label) + 14 + client.textRenderer.getWidth(row.value));
        }

        int width = contentWidth + PADDING * 2;
        int titleHeight = showTitle ? 12 : 0;
        int height = PADDING * 2 + titleHeight + rows.size() * ROW_HEIGHT;
        int x = client.getWindow().getScaledWidth() - width - 7;
        int y = 28;

        context.fill(x, y, x + width, y + height, BACKGROUND);
        context.fill(x, y, x + 2, y + height, ACCENT);
        if (showTitle) {
            context.drawText(client.textRenderer, Text.literal(config.statsTitle), x + PADDING, y + PADDING,
                    VALUE_COLOR, false);
        }

        int rowY = y + PADDING + titleHeight;
        for (StatRow row : rows) {
            context.drawText(client.textRenderer, row.label, x + PADDING, rowY, LABEL_COLOR, false);
            int valueX = x + width - PADDING - client.textRenderer.getWidth(row.value);
            context.drawText(client.textRenderer, row.value, valueX, rowY, VALUE_COLOR, false);
            rowY += ROW_HEIGHT;
        }
    }

    private static List<StatRow> collectRows(ClientPlayerEntity player, ChatCraftConfig config) {
        List<StatRow> rows = new ArrayList<>(10);
        if (config.statsDeaths) rows.add(row(player, "Deaths", Stats.DEATHS));
        if (config.statsPlaytime) {
            rows.add(new StatRow(Text.literal("Playtime"), formatPlaytime(value(player, Stats.PLAY_TIME))));
        }
        if (config.statsMobKills) rows.add(row(player, "Mob kills", Stats.MOB_KILLS));
        if (config.statsPlayerKills) rows.add(row(player, "Player kills", Stats.PLAYER_KILLS));
        if (config.statsDistance) {
            rows.add(new StatRow(Text.literal("Walked"), formatDistance(value(player, Stats.WALK_ONE_CM))));
        }
        if (config.statsJumps) rows.add(row(player, "Jumps", Stats.JUMP));
        PlatformStatsService platformStats = ChatCraftClient.platformStats();
        if (config.statsTwitchFollowers) {
            rows.add(new StatRow(platformLabel(TWITCH_ICON, "Followers"),
                    formatCount(platformStats.twitchFollowers())));
        }
        if (config.statsTwitchViewers) {
            rows.add(new StatRow(platformLabel(TWITCH_ICON, "Viewers"),
                    formatCount(platformStats.twitchViewers())));
        }
        if (config.statsYoutubeSubscribers) {
            rows.add(new StatRow(platformLabel(YOUTUBE_ICON, "Subscribers"),
                    formatCount(platformStats.youtubeSubscribers())));
        }
        if (config.statsYoutubeViewers) {
            rows.add(new StatRow(platformLabel(YOUTUBE_ICON, "Viewers"),
                    formatCount(platformStats.youtubeViewers())));
        }
        return rows;
    }

    private static boolean hasWorldStatsEnabled(ChatCraftConfig config) {
        return config.statsDeaths || config.statsPlaytime || config.statsMobKills
                || config.statsPlayerKills || config.statsDistance || config.statsJumps;
    }

    private static StatRow row(ClientPlayerEntity player, String label, Identifier statistic) {
        return new StatRow(Text.literal(label), Integer.toString(value(player, statistic)));
    }

    private static Text platformLabel(String icon, String label) {
        MutableText text = Text.empty();
        text.append(Text.literal(icon).styled(style -> style.withFont(PLATFORM_ICONS_FONT).withColor(0xFFFFFF)));
        return text.append(Text.literal(" " + label).styled(style -> style.withColor(LABEL_COLOR & 0xFFFFFF)));
    }

    private static int value(ClientPlayerEntity player, Identifier statistic) {
        return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(statistic));
    }

    private static String formatPlaytime(int ticks) {
        long totalSeconds = Math.max(0L, ticks / 20L);
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private static String formatDistance(int centimeters) {
        if (centimeters >= 100_000) {
            return String.format(Locale.ROOT, "%.1f km", centimeters / 100_000.0);
        }
        return String.format(Locale.ROOT, "%.0f m", centimeters / 100.0);
    }

    private static String formatCount(Long value) {
        if (value == null) return "—";
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 10_000) return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        return Long.toString(value);
    }

    private record StatRow(Text label, String value) { }
}
