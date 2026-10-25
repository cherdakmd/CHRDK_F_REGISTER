package ru.cherdakmd.chrdktreasures;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdktreasures_...%}.
 *
 * <p>Только сохранённые данные — ни одного лишнего расчёта: плейсхолдер может
 * запрашиваться десятки раз в секунду в таб-листе и скорборде, поэтому здесь
 * нет ни бросков, ни обхода каталога. Всё, что нужно, уже лежит в профиле
 * игрока.</p>
 *
 * <ul>
 *   <li>{@code found} — найдено кладов;</li>
 *   <li>{@code mined} — добыто блоков;</li>
 *   <li>{@code collection} / {@code total} — собрано находок и всего в каталоге;</li>
 *   <li>{@code rank} / {@code rank_name} — ид и русское имя звания;</li>
 *   <li>{@code streak} / {@code best_streak} — текущая серия и рекорд;</li>
 *   <li>{@code next} — блоков до следующей редкости;</li>
 *   <li>{@code tier_<ид>} — находок этой редкости;</li>
 *   <li>{@code chance_<ид>} — шанс редкости в процентах с блока;</li>
 *   <li>{@code maps} — сколько активных клад-карт (1.4.153);</li>
 *   <li>{@code map_digs} — сколько блоков ещё копать по всем картам;</li>
 *   <li>{@code map_distance} — блоков до ближайшей карты в этом мире (пусто — нет);</li>
 *   <li>{@code shards} / {@code shards_spent} — баланс осколков кладов и сколько
 *       уже потрачено в обменнике (1.4.166).</li>
 * </ul>
 */
public final class TreasurePlaceholders extends PlaceholderExpansion {

    private final TreasuresPlugin plugin;

    TreasurePlaceholders(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdktreasures";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if ("total".equals(key)) {
            return Integer.toString(TreasureRegistry.size());
        }
        if (key.startsWith("chance_")) {
            TreasureTier tier = TreasureTier.byId(key.substring("chance_".length()));
            return tier == null ? null : format(plugin.config().chance(tier));
        }
        if (player == null) {
            return "";
        }
        Player online = player.isOnline() ? player.getPlayer() : null;
        TreasureService service = plugin.treasures();
        if (online == null) {
            // Офлайн: показываем только то, что лежит в хранилище и не требует мира.
            return switch (key) {
                case "found" -> Long.toString(plugin.store().found(player.getUniqueId().toString()));
                case "collection" -> Integer.toString(
                        plugin.store().foundItems(player.getUniqueId().toString()));
                case "streak" -> Integer.toString(
                        plugin.store().streak(player.getUniqueId().toString()));
                case "best_streak" -> Integer.toString(
                        plugin.store().bestStreak(player.getUniqueId().toString()));
                case "shards" -> Long.toString(plugin.store().shards(player.getUniqueId().toString()));
                case "shards_spent" -> Long.toString(
                        plugin.store().shardsSpent(player.getUniqueId().toString()));
                // Карты лежат в инвентаре: без онлайна их просто не прочитать.
                case "maps", "map_digs", "map_distance" -> "0";
                default -> "";
            };
        }
        return switch (key) {
            case "found" -> Long.toString(service.found(online));
            case "mined" -> Long.toString(service.mined(online));
            case "collection" -> Integer.toString(service.foundItems(online));
            case "rank" -> service.rank(online).id();
            case "rank_name" -> plugin.messages().plain(
                    "rank." + service.rank(online).id());
            case "streak" -> Integer.toString(service.streak(online));
            case "best_streak" -> Integer.toString(service.bestStreak(online));
            case "shards" -> Long.toString(service.shards(online));
            case "shards_spent" -> Long.toString(service.shardsSpent(online));
            case "streak_left" -> Long.toString(service.streakRemaining(online) / 1000L);
            case "next" -> Long.toString(service.blocksToNextTier(online));
            case "maps" -> Integer.toString(plugin.maps().activeCount(online));
            case "map_digs" -> Integer.toString(digsLeft(online));
            case "map_distance" -> mapDistance(online);
            default -> tierValue(online, key);
        };
    }

    /** Сколько блоков копать по всем живым картам игрока. */
    private int digsLeft(Player player) {
        int left = 0;
        long now = System.currentTimeMillis();
        for (TreasureMapService.Slot slot : plugin.maps().active(player)) {
            if (!slot.map().expired(now)) {
                left += slot.map().remaining();
            }
        }
        return left;
    }

    /** Блоков до ближайшей карты в текущем мире; пусто — карт нет. */
    private String mapDistance(Player player) {
        TreasureMapHint best = null;
        long now = System.currentTimeMillis();
        String world = player.getWorld().getName();
        for (TreasureMapService.Slot slot : plugin.maps().active(player)) {
            if (slot.map().expired(now)) {
                continue;
            }
            TreasureMapHint hint = TreasureMapHint.of(slot.map(), world,
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ());
            if (hint.sameWorld() && (best == null || hint.distance() < best.distance())) {
                best = hint;
            }
        }
        return best == null ? "" : Integer.toString(best.distance());
    }

    private String tierValue(Player player, String key) {
        if (!key.startsWith("tier_")) {
            return null;
        }
        TreasureTier tier = TreasureTier.byId(key.substring("tier_".length()));
        if (tier == null) {
            return null;
        }
        return Long.toString(plugin.treasures().tierCount(player, tier));
    }

    private static String format(double percent) {
        double rounded = percent >= 1.0
                ? Math.round(percent * 100.0) / 100.0
                : Math.round(percent * 10_000.0) / 10_000.0;
        return Double.toString(rounded);
    }
}
