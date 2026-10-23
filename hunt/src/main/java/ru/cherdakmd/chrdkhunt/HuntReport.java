package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.List;
import java.util.UUID;

/**
 * Текстовые отчёты охоты: статистика, рейтинг и справка (1.4.155).
 *
 * <p>И команда, и меню показывают одно и то же, поэтому текст собран здесь:
 * правила не расходятся между {@code /hunt stats} и книгой в меню.</p>
 */
public final class HuntReport {

    private final HuntPlugin plugin;

    HuntReport(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    /** Статистика игрока: свой ник, онлайн-игрок или запись из {@code hunt.json}. */
    public boolean stats(CommandSender sender, String target) {
        if (sender == null) {
            return false;
        }
        if (target == null || target.isBlank()) {
            if (sender instanceof Player player) {
                target = player.getName();
            } else {
                plugin.messages().send(sender, "player-only");
                return false;
            }
        }
        UUID id;
        String name;
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            HuntStore.Row row = plugin.store().byName(target);
            if (row == null) {
                plugin.messages().send(sender, "stats-player-not-found", "player", target);
                return false;
            }
            id = row.id();
            name = row.name();
        }
        HuntStore.PlayerData data = plugin.store().player(id);
        if (data == null) {
            plugin.messages().send(sender, "stats-player-not-found", "player", target);
            return false;
        }
        long earned = data.earned();
        long coins = data.coins();
        long exchangeLimit = plugin.config().exchangePerDay();
        long exchangeToday = plugin.store().exchangeToday(id, System.currentTimeMillis());
        plugin.messages().send(sender, "stats-header", "player", name);
        plugin.messages().send(sender, "stats-coins",
                "coins", String.valueOf(coins),
                "earned", String.valueOf(earned));
        plugin.messages().send(sender, "stats-rank",
                "rank", Messages.trusted(plugin.service().rankText(earned)));
        long left = plugin.service().coinsToNextRank(earned);
        if (left > 0L) {
            String next = plugin.config().nextRankId(earned);
            plugin.messages().send(sender, "stats-next",
                    "left", String.valueOf(left),
                    "rank", Messages.trusted(plugin.messages().raw("rank." + next)));
        } else {
            plugin.messages().send(sender, "stats-rank-max");
        }
        plugin.messages().send(sender, "stats-shards",
                "shards", String.valueOf(plugin.store().totalShards(id)));
        plugin.messages().send(sender, "stats-keys",
                "keys", String.valueOf(plugin.store().totalKeys(id)));
        plugin.messages().send(sender, "stats-kills",
                "kills", String.valueOf(data.killsTotal()));
        plugin.messages().send(sender, "stats-trophies",
                "trophies", String.valueOf(data.trophies().size()),
                "total", String.valueOf(plugin.config().trophies().size()));
        plugin.messages().send(sender, "stats-exchange",
                "today", String.valueOf(exchangeToday),
                "limit", exchangeLimit <= 0 ? "0" : String.valueOf(exchangeLimit));
        for (HuntFamily family : plugin.config().families()) {
            long kills = data.kills(family.id());
            long shards = data.shards(family.id());
            long keys = data.keys(family.id());
            if (kills <= 0L && shards <= 0L && keys <= 0L) {
                continue;
            }
            plugin.messages().send(sender, "stats-family",
                    "family", family.displayName(),
                    "kills", String.valueOf(kills),
                    "shards", String.valueOf(shards),
                    "shard", family.shardName(),
                    "keys", String.valueOf(keys),
                    "key", family.keyName());
        }
        plugin.messages().send(sender, "stats-footer");
        return true;
    }

    /** Рейтинг охотников по заработанным монетам. */
    public boolean top(CommandSender sender) {
        if (sender == null) {
            return false;
        }
        List<HuntStore.Row> rows = plugin.store().top(plugin.config().topSize());
        if (rows.isEmpty()) {
            plugin.messages().send(sender, "top-empty");
            return true;
        }
        plugin.messages().send(sender, "top-header");
        int place = 1;
        for (HuntStore.Row row : rows) {
            plugin.messages().send(sender, "top-row",
                    "num", String.valueOf(place++),
                    "player", row.name(),
                    "earned", String.valueOf(row.earned()),
                    "kills", String.valueOf(row.kills()),
                    "trophies", String.valueOf(row.trophies()),
                    "rank", Messages.trusted(plugin.service().rankText(row.earned())));
        }
        plugin.messages().send(sender, "top-footer");
        return true;
    }

    /** Справка по командам охоты. */
    public void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        plugin.messages().send(sender, "help-stats");
        plugin.messages().send(sender, "help-shop");
        plugin.messages().send(sender, "help-buy");
        plugin.messages().send(sender, "help-exchange");
        plugin.messages().send(sender, "help-keys");
        plugin.messages().send(sender, "help-chest");
        plugin.messages().send(sender, "help-trophies");
        plugin.messages().send(sender, "help-top");
        plugin.messages().send(sender, "help-footer");
    }
}
