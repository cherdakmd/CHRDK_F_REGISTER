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
import java.util.List;
import java.util.Locale;

/** /bpadmin — reload, сводка сезона, сброс прогресса игрока. */
final class BattlePassAdminCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());

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
                int missions = plugin.season().weeks().stream()
                        .mapToInt(w -> w.missions().size()).sum();
                plugin.messages().send(sender, "admin-reloaded",
                        "weeks", plugin.season().weeks().size(), "missions", missions);
                return true;
            }
            case "info" -> {
                BattlePassConfig.Season season = plugin.season();
                long now = System.currentTimeMillis();
                plugin.messages().send(sender, "admin-info",
                        "season", Messages.trusted(Messages.legacyToMini(season.name())),
                        "start", DATE.format(Instant.ofEpochMilli(season.startEpochMillis())),
                        "week", Math.max(1, BattlePassConfig.currentWeek(season, now)),
                        "weeks_total", season.weeks().size());
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
            default -> {
                plugin.messages().send(sender, "usage-admin");
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("reload", "info", "reset")
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
