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

/** /ranks [ник] — лестница и свой (или чужой) стаж со званием. */
final class RanksCommandHandler implements CommandExecutor, TabCompleter {

    private final RanksPlugin plugin;

    RanksCommandHandler(RanksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 1) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        plugin.messages().send(sender, "header");
        for (RankLadder.Rank rank : plugin.config().ladder()) {
            plugin.messages().send(sender, "line", "rank", rank.displayName(),
                    "hours", rank.hours());
        }
        Player target;
        if (args.length == 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                plugin.messages().send(sender, "not-found", "player", args[0]);
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "usage");
                return true;
            }
            target = player;
        }
        long total = plugin.store().secondsOf(target.getUniqueId());
        int tier = RankLadder.tierIndex(plugin.config().ladder(), total);
        String rankName = tier >= 0
                ? plugin.config().ladder().get(tier).displayName()
                : plugin.messages().raw("no-rank");
        String playtime = RankLadder.formatPlaytime(total);
        if (args.length == 1) {
            plugin.messages().send(sender, "player-line",
                    "player", target.getName(), "rank", rankName, "playtime", playtime);
            return true;
        }
        if (tier >= 0) {
            plugin.messages().send(sender, "your-rank", "rank", rankName, "playtime", playtime);
        } else {
            plugin.messages().send(sender, "no-rank");
            return true;
        }
        RankLadder.NextRank next = RankLadder.nextUp(plugin.config().ladder(), total);
        if (next == null) {
            plugin.messages().send(sender, "top-rank");
        } else {
            plugin.messages().send(sender, "next", "rank", next.rank().displayName(),
                    "left", RankLadder.formatHoursLeft(next.hoursLeft()));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
