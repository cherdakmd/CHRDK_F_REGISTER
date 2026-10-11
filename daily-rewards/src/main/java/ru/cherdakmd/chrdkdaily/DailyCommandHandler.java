package ru.cherdakmd.chrdkdaily;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /daily (игроки) и /dailyadmin (администраторы). */
public final class DailyCommandHandler implements TabExecutor {

    private final DailyRewardsPlugin plugin;

    public DailyCommandHandler(DailyRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "daily.no-api");
            return true;
        }
        if ("dailyadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        return handleDaily(sender, args);
    }

    /* ------------------------------- /daily ------------------------------- */

    private boolean handleDaily(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "daily.usage");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "daily.need-auth");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "claim" -> {
                if (!plugin.service().canClaim(player)) {
                    plugin.messages().send(player, "daily.already-claimed",
                            "streak", plugin.store().progressOf(player.getUniqueId()).streak);
                    return true;
                }
                DailyService.ClaimResult result = plugin.service().claim(player);
                if (result == null) {
                    plugin.messages().send(player, "daily.already-claimed",
                            "streak", plugin.store().progressOf(player.getUniqueId()).streak);
                } else {
                    announceClaim(player, result);
                }
            }
            case "info" -> {
                DailyStore.Progress progress = plugin.store().progressOf(player.getUniqueId());
                boolean claimed = progress.lastClaimDay >= DailyStore.dayOf(System.currentTimeMillis());
                plugin.messages().send(player, "daily.info",
                        "streak", progress.streak, "total", progress.totalClaims,
                        "claimed", plugin.messages().raw(claimed ? "daily.claimed-yes" : "daily.claimed-no"));
            }
            default -> plugin.messages().send(player, "daily.usage");
        }
        return true;
    }

    /** Объявляет содержимое выданной награды (используется командой и GUI). */
    public void announceClaim(Player player, DailyService.ClaimResult result) {
        DailyConfig.DayReward day = plugin.config().rewardForStreakDay(result.streak());
        plugin.messages().send(player, "daily.claim-header", "day", day.day());
        if (!result.items().isEmpty()) {
            plugin.messages().send(player, "daily.claim-items",
                    "items", DailyService.itemsText(result.items()));
        }
        if (result.passDays() > 0) {
            plugin.messages().send(player, "daily.claim-pass-days", "days", result.passDays());
        }
        plugin.messages().send(player, "daily.claim-streak", "streak", result.streak());
    }

    /* ------------------------------- /dailyadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadDailyConfig();
            plugin.messages().send(sender, "admin.reloaded");
            return true;
        }
        if (args.length >= 2 && "lookup".equalsIgnoreCase(args[0])) {
            String name = args[1].trim();
            Player online = Bukkit.getPlayerExact(name);
            UUID uuid = online != null ? online.getUniqueId()
                    : (plugin.coreApi() != null ? uuidOf(name) : null);
            if (uuid == null) {
                plugin.messages().send(sender, "admin.lookup",
                        "player", name, "streak", "—", "total", "—",
                        "claimed", plugin.messages().raw("daily.claimed-no"));
                return true;
            }
            DailyStore.Progress progress = plugin.store().progressOf(uuid);
            boolean claimed = progress.lastClaimDay >= DailyStore.dayOf(System.currentTimeMillis());
            plugin.messages().send(sender, "admin.lookup",
                    "player", name, "streak", progress.streak, "total", progress.totalClaims,
                    "claimed", plugin.messages().raw(claimed ? "daily.claimed-yes" : "daily.claimed-no"));
            return true;
        }
        if (args.length >= 3 && "set".equalsIgnoreCase(args[0])) {
            String name = args[1].trim();
            int streak;
            try {
                streak = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                plugin.messages().send(sender, "admin.invalid-number", "value", args[2]);
                return true;
            }
            Player online = Bukkit.getPlayerExact(name);
            UUID uuid = online != null ? online.getUniqueId() : uuidOf(name);
            if (uuid == null) {
                plugin.messages().send(sender, "admin.lookup", "player", name,
                        "streak", "—", "total", "—",
                        "claimed", plugin.messages().raw("daily.claimed-no"));
                return true;
            }
            plugin.store().setStreak(uuid, streak);
            plugin.messages().send(sender, "admin.set-ok", "player", name, "streak", Math.max(0, streak));
            return true;
        }
        plugin.messages().send(sender, "admin.usage");
        return true;
    }

    private UUID uuidOf(String name) {
        // UUID берём из онлайна; оффлайн-просмотр — по прямому совпадению ника в ядре не делаем
        @SuppressWarnings("deprecation")
        Player cached = Bukkit.getPlayer(name);
        return cached == null ? null : cached.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("dailyadmin".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("lookup", "set", "reload").stream()
                        .filter(option -> option.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if (args.length == 1) {
            List.of("claim", "info").stream().filter(option -> option.startsWith(prefix)).forEach(matches::add);
        }
        return matches;
    }
}
