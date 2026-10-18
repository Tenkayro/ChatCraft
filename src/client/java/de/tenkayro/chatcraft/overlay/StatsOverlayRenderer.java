package de.tenkayro.chatcraft.overlay;

import de.tenkayro.chatcraft.config.ChatCraftConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.stat.Stats;
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

    private StatsOverlayRenderer() { }

    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden) return;

        List<StatRow> rows = collectRows(player, ChatCraftConfig.get());
        if (rows.isEmpty()) return;

        int contentWidth = client.textRenderer.getWidth("STATS");
        for (StatRow row : rows) {
            contentWidth = Math.max(contentWidth,
                    client.textRenderer.getWidth(row.label) + 14 + client.textRenderer.getWidth(row.value));
        }

        int width = contentWidth + PADDING * 2;
        int height = PADDING * 2 + 9 + 3 + rows.size() * ROW_HEIGHT;
        int x = client.getWindow().getScaledWidth() - width - 7;
        int y = 28;

        context.fill(x, y, x + width, y + height, BACKGROUND);
        context.fill(x, y, x + 2, y + height, ACCENT);
        context.drawText(client.textRenderer, Text.literal("STATS"), x + PADDING, y + PADDING,
                VALUE_COLOR, false);

        int rowY = y + PADDING + 12;
        for (StatRow row : rows) {
            context.drawText(client.textRenderer, row.label, x + PADDING, rowY, LABEL_COLOR, false);
            int valueX = x + width - PADDING - client.textRenderer.getWidth(row.value);
            context.drawText(client.textRenderer, row.value, valueX, rowY, VALUE_COLOR, false);
            rowY += ROW_HEIGHT;
        }
    }

    private static List<StatRow> collectRows(ClientPlayerEntity player, ChatCraftConfig config) {
        List<StatRow> rows = new ArrayList<>(6);
        if (config.statsDeaths) rows.add(row(player, "Deaths", Stats.DEATHS));
        if (config.statsPlaytime) {
            rows.add(new StatRow("Playtime", formatPlaytime(value(player, Stats.PLAY_TIME))));
        }
        if (config.statsMobKills) rows.add(row(player, "Mob kills", Stats.MOB_KILLS));
        if (config.statsPlayerKills) rows.add(row(player, "Player kills", Stats.PLAYER_KILLS));
        if (config.statsDistance) {
            rows.add(new StatRow("Walked", formatDistance(value(player, Stats.WALK_ONE_CM))));
        }
        if (config.statsJumps) rows.add(row(player, "Jumps", Stats.JUMP));
        return rows;
    }

    private static StatRow row(ClientPlayerEntity player, String label, Identifier statistic) {
        return new StatRow(label, Integer.toString(value(player, statistic)));
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

    private record StatRow(String label, String value) { }
}
