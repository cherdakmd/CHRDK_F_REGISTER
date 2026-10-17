package ru.cherdakmd.chrdkbp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /battlepass [claim <id>] — прогресс сезона и получение наград. */
final class BattlePassCommandHandler implements CommandExecutor, TabCompleter {

    private final BattlePassPlugin plugin;

    BattlePassCommandHandler(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "usage", "label", label);
            return true;
        }
        if (args.length == 0) {
            showProgress(player, label);
            return true;
        }
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0])) {
            claim(player, args[1]);
            return true;
        }
        plugin.messages().send(sender, "usage", "label", label);
        return true;
    }

    private void showProgress(Player player, String label) {
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        long now = System.currentTimeMillis();
        int current = Math.max(1, BattlePassConfig.currentWeek(season, now));
        plugin.messages().send(player, "header", "season", season.name(),
                "week", current, "weeks_total", season.weeks().size());
        for (int i = 0; i < season.weeks().size(); i++) {
            BattlePassConfig.WeekDef week = season.weeks().get(i);
            if (!BattlePassConfig.isUnlocked(season, i, now)) {
                plugin.messages().send(player, "week-locked", "week", i + 1,
                        "days", BattlePassConfig.daysUntilUnlock(season, i, now));
                break;
            }
            plugin.messages().send(player, "week-title", "week", i + 1);
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                boolean claimed = plugin.store().isClaimed(player.getUniqueId(), mission.id(), "FREE");
                if (claimed) {
                    plugin.messages().send(player, "mission-claimed", "desc", mission.description());
                    continue;
                }
                int progress = plugin.store().progressOf(player.getUniqueId(), mission.id());
                String state = progress >= mission.target()
                        ? "<green>✔</green>" : "<gray>•</gray>";
                plugin.messages().send(player, "mission",
                        "state", state, "desc", mission.description(),
                        "progress", Math.min(progress, mission.target()),
                        "target", mission.target());
            }
        }
    }

    /** Выдача наград: FREE всем, VIP при активной проходке. Можно добивать и старые волны. */
    private void claim(Player player, String rawId) {
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        BattlePassConfig.Found found = BattlePassConfig.find(season, rawId);
        if (found == null) {
            plugin.messages().send(player, "unknown", "id", rawId);
            return;
        }
        long now = System.currentTimeMillis();
        if (!BattlePassConfig.isUnlocked(season, found.weekIndex(), now)) {
            plugin.messages().send(player, "locked", "id", found.mission().id());
            return;
        }
        BattlePassConfig.MissionDef mission = found.mission();
        int progress = plugin.store().progressOf(player.getUniqueId(), mission.id());
        if (progress < mission.target()) {
            plugin.messages().send(player, "not-ready", "id", mission.id(),
                    "progress", progress, "target", mission.target());
            return;
        }
        boolean vip = plugin.coreApi() != null && plugin.coreApi().hasActivePass(player.getName());
        if (mission.freeDays() > 0
                && plugin.store().markClaimed(player.getUniqueId(), mission.id(), "FREE")) {
            grant(player, mission, mission.freeDays(), "battlepass:" + mission.id() + ":free", false);
        }
        if (mission.vipDays() > 0) {
            if (!vip) {
                plugin.messages().send(player, "vip-denied", "id", mission.id());
            } else if (plugin.store().markClaimed(player.getUniqueId(), mission.id(), "VIP")) {
                grant(player, mission, mission.vipDays(), "battlepass:" + mission.id() + ":vip", true);
            }
        }
    }

    private void grant(Player player, BattlePassConfig.MissionDef mission, int days,
                       String source, boolean vip) {
        plugin.coreApi().grantPass(player.getName(), days, source);
        long until = plugin.coreApi().passUntil(player.getName());
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy").withZone(java.time.ZoneId.systemDefault());
        plugin.messages().send(player, vip ? "reward-vip" : "reward",
                "id", mission.id(), "days", days,
                "until", fmt.format(java.time.Instant.ofEpochMilli(until)));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return "claim".startsWith(args[0].toLowerCase(Locale.ROOT))
                    ? List.of("claim") : List.of();
        }
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0]) && plugin.season() != null) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (BattlePassConfig.WeekDef week : plugin.season().weeks()) {
                for (BattlePassConfig.MissionDef mission : week.missions()) {
                    if (mission.id().startsWith(prefix)) {
                        ids.add(mission.id());
                    }
                }
            }
            return ids;
        }
        return List.of();
    }
}
