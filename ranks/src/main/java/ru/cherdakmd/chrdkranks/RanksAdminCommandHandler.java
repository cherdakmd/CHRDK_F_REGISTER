package ru.cherdakmd.chrdkranks;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** /ranksadmin — reload, ручная установка стажа, сводка. */
final class RanksAdminCommandHandler implements CommandExecutor, TabCompleter {

    private final RanksPlugin plugin;

    RanksAdminCommandHandler(RanksPlugin plugin) {
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
                plugin.reloadRanksConfig();
                plugin.messages().send(sender, "reloaded",
                        "count", plugin.config().ladder().size());
                return true;
            }
            case "info" -> {
                plugin.messages().send(sender, "info",
                        "count", plugin.config().ladder().size(),
                        "sort", plugin.messages().raw(
                                plugin.config().sortTab() ? "state-on" : "state-off"),
                        "tick", plugin.config().tickSeconds());
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "usage-admin");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    plugin.messages().send(sender, "not-found", "player", args[1]);
                    return true;
                }
                long hours;
                try {
                    hours = Long.parseLong(args[2]);
                    if (hours < 0 || hours > 1_000_000) {
                        plugin.messages().send(sender, "hours-number");
                        return true;
                    }
                } catch (NumberFormatException exception) {
                    plugin.messages().send(sender, "hours-number");
                    return true;
                }
                plugin.store().setSeconds(target.getUniqueId(), hours * 3600L);
                plugin.service().evaluate(target, hours * 3600L);
                plugin.messages().send(sender, "set-ok", "player", target.getName(),
                        "playtime", RankLadder.formatPlaytime(hours * 3600L));
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
            return java.util.stream.Stream.of("reload", "info", "set")
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "set".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
