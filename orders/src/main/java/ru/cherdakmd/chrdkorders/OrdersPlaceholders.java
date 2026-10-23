package ru.cherdakmd.chrdkorders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkorders_...%} (1.4.154).
 *
 * <p>Только сохранённые данные: ни генерации, ни обхода инвентаря. Плейсхолдер
 * может спрашиваться десятки раз в секунду в табе и скорборде, поэтому всё
 * нужное уже лежит в {@code orders.json}.</p>
 *
 * <ul>
 *   <li>{@code orders} — заказов на доске (работает и без игрока);</li>
 *   <li>{@code available} — из них доступно игроку по репутации;</li>
 *   <li>{@code reputation} — репутация у ботов;</li>
 *   <li>{@code rank} / {@code rank_name} — ид звания и русское имя;</li>
 *   <li>{@code completed} — сдано заказов за всё время;</li>
 *   <li>{@code today} / {@code limit} — сдано сегодня и суточный лимит;</li>
 *   <li>{@code next} — репутации до следующего звания;</li>
 *   <li>{@code cooldown} — секунд до следующей сдачи.</li>
 * </ul>
 */
public final class OrdersPlaceholders extends PlaceholderExpansion {

    private final OrdersPlugin plugin;

    OrdersPlaceholders(OrdersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkorders";
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
        if ("orders".equals(key)) {
            return Integer.toString(plugin.store().size());
        }
        int limit = plugin.config().maxPerDay();
        if ("limit".equals(key)) {
            return limit <= 0 ? "0" : Integer.toString(limit);
        }
        if (player == null) {
            return "";
        }
        UUID id = player.getUniqueId();
        int reputation = plugin.store().reputation(id);
        int left = plugin.config().nextRankThreshold(reputation);
        Player online = player.isOnline() ? player.getPlayer() : null;
        return switch (key) {
            case "reputation" -> Integer.toString(reputation);
            case "rank" -> plugin.config().rankId(reputation);
            case "rank_name" -> plugin.messages().plain("rank." + plugin.config().rankId(reputation));
            case "completed" -> Integer.toString(plugin.store().completed(id));
            case "today" -> Integer.toString(plugin.store().today(id, System.currentTimeMillis()));
            case "next" -> left < 0 ? "0" : Integer.toString(Math.max(0, left - reputation));
            case "available" -> Integer.toString(online == null ? 0 : plugin.service().availableFor(online));
            case "cooldown" -> Long.toString(online == null ? 0L : (plugin.service().cooldownLeft(online) + 999L) / 1000L);
            default -> "";
        };
    }
}
