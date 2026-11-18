package ru.cherdakmd.chrdkanticheat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Команда модераторов {@code /ac}: карточка нарушителя, топ суток,
 * личные алерты и перезагрузка конфига моста.
 */
final class AcCommand implements CommandExecutor {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int HISTORY_ROWS = 20;
    private static final int TOP_ROWS = 10;

    private final AnticheatPlugin plugin;

    AcCommand(AnticheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "history" -> history(sender, args);
            case "top" -> top(sender);
            case "alerts" -> alerts(sender);
            case "reload" -> reload(sender);
            default -> plugin.messages().send(sender, "usage");
        }
        return true;
    }

    private void history(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkanticheat.mod")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "usage");
            return;
        }
        List<FlagRecord> records = plugin.journal().history(args[1]);
        if (records.isEmpty()) {
            plugin.messages().send(sender, "card-empty", "player", args[1]);
            return;
        }
        FlagJournal.Summary summary = FlagJournal.summarize(records);
        plugin.messages().send(sender, "card-header", "player", args[1]);
        plugin.messages().send(sender, "card-summary",
                "total", String.valueOf(summary.total()),
                "first", TIME.format(Instant.ofEpochMilli(summary.first())),
                "last", TIME.format(Instant.ofEpochMilli(summary.last())));
        plugin.messages().send(sender, "card-checks", "checks", formatChecks(summary));
        plugin.messages().send(sender, "card-places", "places", String.join(", ", summary.places()));
        List<FlagRecord> tail = records.subList(Math.max(0, records.size() - HISTORY_ROWS), records.size());
        for (FlagRecord record : tail) {
            plugin.messages().send(sender, "history-row",
                    "time", TIME.format(Instant.ofEpochMilli(record.time())),
                    "check", record.check(),
                    "vl", String.valueOf(record.vl()),
                    "ping", String.valueOf(record.ping()),
                    "world", record.world(),
                    "x", String.valueOf((int) record.x()),
                    "y", String.valueOf((int) record.y()),
                    "z", String.valueOf((int) record.z()));
        }
    }

    private void top(CommandSender sender) {
        if (!sender.hasPermission("chrdkanticheat.mod")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        long since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        List<FlagJournal.TopEntry> entries = plugin.journal().topSince(since);
        if (entries.isEmpty()) {
            plugin.messages().send(sender, "top-empty");
            return;
        }
        plugin.messages().send(sender, "top-header");
        int num = 0;
        for (FlagJournal.TopEntry entry : entries) {
            if (++num > TOP_ROWS) {
                break;
            }
            plugin.messages().send(sender, "top-row",
                    "num", String.valueOf(num),
                    "player", entry.player(),
                    "checks", String.join(", ", entry.checks()),
                    "total", String.valueOf(entry.total()));
        }
    }

    private void alerts(CommandSender sender) {
        if (!sender.hasPermission("chrdkanticheat.alerts")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "usage");
            return;
        }
        if (plugin.alertsDisabled().remove(player.getUniqueId())) {
            plugin.messages().send(sender, "alerts-on");
        } else {
            plugin.alertsDisabled().add(player.getUniqueId());
            plugin.messages().send(sender, "alerts-off");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("chrdkanticheat.admin")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        plugin.reloadSettings();
        plugin.messages().send(sender, "reloaded");
    }

    private static String formatChecks(FlagJournal.Summary summary) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Long> entry : summary.checkCounts().entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getKey()).append(" ×").append(entry.getValue())
                    .append(" (макс ×").append(summary.checkMaxVl().get(entry.getKey())).append(")");
        }
        return text.toString();
    }
}
