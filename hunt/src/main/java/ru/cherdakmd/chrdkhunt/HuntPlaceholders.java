package ru.cherdakmd.chrdkhunt;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkhunt_...%} (1.4.155).
 *
 * <p>Только сохранённые данные: ни генерации, ни обхода инвентаря. Плейсхолдер
 * может спрашиваться десятки раз в секунду в табе и скорборде, поэтому всё
 * нужное уже лежит в {@code hunt.json}.</p>
 *
 * <ul>
 *   <li>{@code coins} — монеты в кошельке;</li>
 *   <li>{@code earned} — монеты, заработанные за всё время (звание);</li>
 *   <li>{@code rank} / {@code rank_name} — ид звания и русское имя;</li>
 *   <li>{@code next} — монет до следующего звания;</li>
 *   <li>{@code shards} / {@code keys} — суммы осколков и ключей;</li>
 *   <li>{@code shards_<семейство>} / {@code keys_<семейство>} — по семейству;</li>
 *   <li>{@code kills} / {@code kills_<семейство>} — убийства;</li>
 *   <li>{@code trophies} — собрано трофеев;</li>
 *   <li>{@code today} / {@code exchange_today} / {@code exchange_limit} — сутки UTC.</li>
 * </ul>
 */
public final class HuntPlaceholders extends PlaceholderExpansion {

    private final HuntPlugin plugin;

    HuntPlaceholders(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkhunt";
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
        int limit = plugin.config().exchangePerDay();
        if ("exchange_limit".equals(key)) {
            return Integer.toString(limit);
        }
        if (player == null) {
            return "";
        }
        UUID id = player.getUniqueId();
        HuntStore.PlayerData data = plugin.store().player(id);
        if (data == null) {
            return switch (key) {
                case "coins", "earned", "kills", "trophies", "shards", "keys",
                     "exchange_today", "today" -> "0";
                case "rank" -> plugin.config().rankId(0L);
                case "rank_name" -> plugin.messages().plain("rank." + plugin.config().rankId(0L));
                case "next" -> Long.toString(plugin.service().coinsToNextRank(0L));
                default -> "";
            };
        }
        long earned = data.earned();
        if (key.startsWith("shards_")) {
            HuntFamily family = plugin.config().family(key.substring("shards_".length()));
            return family == null ? "" : Long.toString(data.shards(family.id()));
        }
        if (key.startsWith("keys_")) {
            HuntFamily family = plugin.config().family(key.substring("keys_".length()));
            return family == null ? "" : Long.toString(data.keys(family.id()));
        }
        if (key.startsWith("kills_")) {
            HuntFamily family = plugin.config().family(key.substring("kills_".length()));
            return family == null ? "" : Long.toString(data.kills(family.id()));
        }
        return switch (key) {
            case "coins" -> Long.toString(data.coins());
            case "earned" -> Long.toString(earned);
            case "rank" -> plugin.config().rankId(earned);
            case "rank_name" -> plugin.messages().plain("rank." + plugin.config().rankId(earned));
            case "next" -> Long.toString(plugin.service().coinsToNextRank(earned));
            case "shards" -> Long.toString(plugin.store().totalShards(id));
            case "keys" -> Long.toString(plugin.store().totalKeys(id));
            case "kills" -> Long.toString(data.killsTotal());
            case "trophies" -> Integer.toString(data.trophies().size());
            case "today" -> Long.toString(plugin.store().coinsToday(id, System.currentTimeMillis()));
            case "exchange_today" -> Long.toString(
                    plugin.store().exchangeToday(id, System.currentTimeMillis()));
            default -> "";
        };
    }
}
