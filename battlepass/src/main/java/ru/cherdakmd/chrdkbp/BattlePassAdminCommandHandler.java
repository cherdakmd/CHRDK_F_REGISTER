package ru.cherdakmd.chrdkbp;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.cherdakmd.chrdkregister.Messages;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /bpadmin} — перезагрузка, сводка, сброс, правка прогресса, статистика,
 * список заданий и проверка конфига.
 */
final class BattlePassAdminCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());
    private static final List<String> SUBCOMMANDS = List.of(
            "reload", "info", "reset", "resetall", "addprogress", "setprogress",
            "stats", "list", "validate");

    private final BattlePassPlugin plugin;

    BattlePassAdminCommandHandler(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "usage-admin");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadBattlePassConfig();
                int missions = plugin.missionCount();
                plugin.messages().send(sender, "admin-reloaded",
                        "weeks", plugin.season().weeks().size(), "missions", missions);
                List<String> problems = plugin.problems();
                if (!problems.isEmpty()) {
                    plugin.messages().send(sender, "admin-validate-header",
                            "count", problems.size());
                    for (String problem : problems) {
                        plugin.messages().send(sender, "admin-validate-problem", "problem", problem);
                    }
                }
                return true;
            }
            case "info" -> {
                showInfo(sender);
                return true;
            }
            case "reset" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "usage-admin");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (plugin.store().known(target.getUniqueId())) {
                    plugin.store().reset(target.getUniqueId());
                    plugin.messages().send(sender, "admin-reset", "player", args[1]);
                } else {
                    plugin.messages().send(sender, "admin-not-found", "player", args[1]);
                }
                return true;
            }
            case "resetall" -> {
                int players = plugin.store().resetAll();
                plugin.messages().send(sender, "admin-resetall", "players", players);
                return true;
            }
            case "addprogress", "setprogress" -> {
                return progress(sender, args[0].toLowerCase(Locale.ROOT), args);
            }
            case "stats" -> {
                showStats(sender);
                return true;
            }
            case "list" -> {
                showList(sender);
                return true;
            }
            case "validate" -> {
                showValidate(sender);
                return true;
            }
            default -> {
                plugin.messages().send(sender, "usage-admin");
                return true;
            }
        }
    }

    private void showInfo(CommandSender sender) {
        BattlePassConfig.Season season = plugin.season();
        long now = System.currentTimeMillis();
        plugin.messages().send(sender, "admin-info",
                "season", Messages.trusted(Messages.legacyToMini(season.name())),
                "start", DATE.format(Instant.ofEpochMilli(season.startEpochMillis())),
                "week", Math.max(1, BattlePassConfig.currentWeek(season, now)),
                "weeks_total", season.weeks().size(),
                "missions", plugin.missionCount());
        if (season.hasEnd()) {
            plugin.messages().send(sender, "admin-info-end",
                    "end", DATE.format(Instant.ofEpochMilli(season.endEpochMillis())),
                    "days", BattlePassConfig.daysUntilEnd(season, now));
        } else {
            plugin.messages().send(sender, "admin-info-open");
        }
    }

    /** addprogress/setprogress <ник> <id> <n>. */
    private boolean progress(CommandSender sender, String mode, String[] args) {
        if (args.length < 4) {
            plugin.messages().send(sender, "admin-usage-progress");
            return true;
        }
        BattlePassConfig.Season season = plugin.season();
        BattlePassConfig.Found found = BattlePassConfig.find(season, args[2]);
        if (found == null) {
            plugin.messages().send(sender, "unknown", "id", args[2]);
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(sender, "admin-usage-progress");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID uuid = target.getUniqueId();
        plugin.store().rememberName(uuid, target.getName() == null ? args[1] : target.getName());
        BattlePassConfig.MissionDef mission = found.mission();
        int value = "setprogress".equals(mode)
                ? plugin.store().setProgress(uuid, mission.id(), amount, mission.target())
                : plugin.store().addProgress(uuid, mission.id(), amount, mission.target());
        plugin.messages().send(sender, "admin-progress",
                "player", args[1], "id", mission.id(),
                "progress", value, "target", mission.target());
        return true;
    }

    private void showStats(CommandSender sender) {
        BattlePassConfig.Season season = plugin.season();
        Map<UUID, Map<String, Integer>> progress = plugin.store().progressSnapshot();
        int points = 0;
        int completed = 0;
        for (Map<String, Integer> row : progress.values()) {
            points += BattlePassScore.points(season, row);
            completed += BattlePassScore.completed(season, row);
        }
        int ready = 0;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Integer> row = progress.get(player.getUniqueId());
            if (row == null) {
                continue;
            }
            for (int index = 0; index < season.weeks().size(); index++) {
                if (!BattlePassConfig.isUnlocked(season, index, now)) {
                    break;
                }
                for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                    if (row.getOrDefault(mission.id(), 0) >= mission.target()
                            && mission.freeDays() > 0
                            && !plugin.store().isClaimed(player.getUniqueId(), mission.id(), "FREE")) {
                        ready++;
                    }
                }
            }
        }
        plugin.messages().sendList(sender, "admin-stats",
                "season", Messages.trusted(Messages.legacyToMini(season.name())),
                "week", Math.max(1, BattlePassConfig.currentWeek(season, now)),
                "weeks_total", season.weeks().size(),
                "players", progress.size(),
                "completed", completed,
                "points", points,
                "claimed", plugin.store().claimedRecords(),
                "ready", ready);
    }

    private void showList(CommandSender sender) {
        BattlePassConfig.Season season = plugin.season();
        plugin.messages().send(sender, "admin-list-header",
                "count", plugin.missionCount());
        for (int index = 0; index < season.weeks().size(); index++) {
            for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                plugin.messages().send(sender, "admin-list-line",
                        "week", index + 1,
                        "id", mission.id(),
                        "type", mission.type().name(),
                        "target", mission.target(),
                        "free", mission.freeDays(),
                        "vip", mission.vipDays(),
                        "points", mission.points());
            }
        }
    }

    private void showValidate(CommandSender sender) {
        List<String> problems = plugin.problems();
        if (problems.isEmpty()) {
            plugin.messages().send(sender, "admin-validate-ok",
                    "weeks", plugin.season().weeks().size(),
                    "missions", plugin.missionCount());
            return;
        }
        plugin.messages().send(sender, "admin-validate-header", "count", problems.size());
        for (String problem : problems) {
            plugin.messages().send(sender, "admin-validate-problem", "problem", problem);
        }
        plugin.messages().send(sender, "admin-validate-hint");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && ("reset".equals(sub) || "addprogress".equals(sub)
                || "setprogress".equals(sub))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && ("addprogress".equals(sub) || "setprogress".equals(sub))
                && plugin.season() != null) {
            List<String> ids = new ArrayList<>();
            for (BattlePassConfig.WeekDef week : plugin.season().weeks()) {
                for (BattlePassConfig.MissionDef mission : week.missions()) {
                    ids.add(mission.id());
                }
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
