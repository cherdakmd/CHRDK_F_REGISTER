package ru.cherdakmd.chrdkbp;

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
 * {@code /battlepass [menu|top|info <id>|help|claim [id|all]]} — прогресс сезона,
 * рейтинг, справка и получение наград.
 */
final class BattlePassCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM")
            .withZone(ZoneId.systemDefault());
    private static final List<String> SUBCOMMANDS =
            List.of("claim", "menu", "progress", "top", "info", "help");

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
        if (!player.hasPermission("chrdkbp.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            if (plugin.settings().menuEnabled()) {
                plugin.menu().open(player, 0);
            } else {
                showProgress(player, label);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "claim" -> {
                if (args.length == 1 || (args.length == 2 && "all".equalsIgnoreCase(args[1]))) {
                    plugin.claims().claimAll(player);
                } else if (args.length == 2) {
                    plugin.claims().claimOne(player, args[1]);
                } else {
                    plugin.messages().send(player, "usage", "label", label);
                }
            }
            case "menu", "gui" -> {
                if (!plugin.settings().menuEnabled()) {
                    plugin.messages().send(player, "menu-disabled");
                    return true;
                }
                plugin.menu().open(player, 0);
            }
            case "progress", "text" -> showProgress(player, label);
            case "top", "rating" -> showTop(player, label);
            case "info" -> {
                if (args.length < 2) {
                    plugin.messages().send(player, "usage", "label", label);
                } else {
                    showInfo(player, args[1]);
                }
            }
            case "help", "?" -> plugin.messages().sendList(player, "help", "label", label);
            default -> plugin.messages().send(player, "usage", "label", label);
        }
        return true;
    }

    // ------------------------------------------------------------- прогресс --

    private void showProgress(Player player, String label) {
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        long now = System.currentTimeMillis();
        int current = BattlePassConfig.currentWeek(season, now);
        plugin.messages().send(player, "header",
                "season", BattlePassScreen.described(season.name()),
                "week", Math.max(1, current), "weeks_total", season.weeks().size());
        if (current <= 0) {
            plugin.messages().send(player, "season-not-started",
                    "date", DATE.format(Instant.ofEpochMilli(season.startEpochMillis())));
            return;
        }
        if (season.ended(now)) {
            plugin.messages().send(player, "season-ended",
                    "date", DATE.format(Instant.ofEpochMilli(season.endEpochMillis())));
        }
        plugin.messages().send(player, "subheader",
                "week", current,
                "weeks_total", season.weeks().size(),
                "next", nextWeek(season, current, now));

        long until = plugin.passUntil(player);
        boolean vipActive = plugin.vipActive(player);
        if (vipActive) {
            plugin.messages().send(player, "vip-active",
                    "until", until <= 0 ? "без срока" : DATE.format(Instant.ofEpochMilli(until)));
        } else {
            plugin.messages().send(player, "vip-none");
        }

        boolean premiumActive = plugin.premiumActive(player);
        if (BattlePassPremium.anyRewards(season)) {
            plugin.messages().send(player, premiumActive ? "premium-active" : "premium-none");
        }

        int ready = 0;
        int vipLocked = 0;
        int premiumLocked = 0;
        for (int index = 0; index < season.weeks().size(); index++) {
            if (!BattlePassConfig.isUnlocked(season, index, now)) {
                plugin.messages().send(player, "week-locked",
                        "week", index + 1,
                        "days", BattlePassConfig.daysUntilUnlock(season, index, now),
                        "date", SHORT_DATE.format(
                                Instant.ofEpochMilli(BattlePassConfig.unlockAt(season, index))));
                break;
            }
            List<BattlePassView.Mission> views = views(player, season.weeks().get(index),
                    vipActive, premiumActive);
            int total = views.stream().mapToInt(BattlePassView.Mission::availableTracks).sum();
            int claimed = views.stream().mapToInt(BattlePassView.Mission::claimedTracks).sum();
            if (total > 0 && claimed >= total) {
                plugin.messages().send(player, "week-complete", "week", index + 1);
                continue;
            }
            plugin.messages().send(player, "week-title",
                    "week", index + 1, "claimed", claimed, "total", total);
            for (BattlePassView.Mission view : views) {
                player.sendMessage(plugin.screen().mission(label, view));
                if (view.state() == BattlePassView.State.READY) {
                    ready++;
                } else if (view.state() == BattlePassView.State.PREMIUM_LOCKED) {
                    premiumLocked++;
                } else if (view.state() == BattlePassView.State.VIP_LOCKED) {
                    vipLocked++;
                }
            }
        }
        if (ready > 0) {
            plugin.messages().send(player, "footer-claimable",
                    "count", ready, "label", label);
        } else if (premiumLocked > 0) {
            plugin.messages().send(player, "footer-premium-locked", "count", premiumLocked);
        } else if (vipLocked > 0) {
            plugin.messages().send(player, "footer-vip-locked", "count", vipLocked);
        } else {
            plugin.messages().send(player, "footer-idle");
        }
        Map<String, Integer> progress = plugin.store().progressMap(player.getUniqueId());
        plugin.messages().send(player, "points-footer",
                "points", BattlePassScore.points(season, progress),
                "completed", BattlePassScore.completed(season, progress));
    }

    /** Хвост подзаголовка про следующую волну или пустая строка. */
    private Messages.Trusted nextWeek(BattlePassConfig.Season season, int current, long now) {
        if (current >= season.weeks().size()) {
            return Messages.trusted("");
        }
        String text = plugin.messages().raw("next-week",
                "days", BattlePassConfig.daysUntilUnlock(season, current, now),
                "date", SHORT_DATE.format(
                        Instant.ofEpochMilli(BattlePassConfig.unlockAt(season, current))));
        return Messages.trusted(Messages.legacyToMini(text));
    }

    private List<BattlePassView.Mission> views(Player player, BattlePassConfig.WeekDef week,
                                               boolean vipActive, boolean premiumActive) {
        UUID uuid = player.getUniqueId();
        List<BattlePassView.Mission> views = new ArrayList<>();
        for (BattlePassConfig.MissionDef mission : week.missions()) {
            views.add(new BattlePassView.Mission(mission,
                    plugin.store().progressOf(uuid, mission.id()),
                    plugin.store().isClaimed(uuid, mission.id(), "FREE"),
                    plugin.store().isClaimed(uuid, mission.id(), "VIP"),
                    vipActive,
                    plugin.store().isClaimed(uuid, mission.id(), "PREMIUM"),
                    premiumActive));
        }
        return views;
    }

    // -------------------------------------------------------------- рейтинг --

    private void showTop(Player player, String label) {
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        List<BattlePassScore.Entry> all = BattlePassScore.leaderboard(
                season,
                plugin.store().progressSnapshot(),
                plugin.store().namesSnapshot(),
                plugin.store().claimsSnapshot(),
                0);
        if (all.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return;
        }
        plugin.messages().send(player, "top-header", "count", all.size());
        int shown = Math.min(all.size(), 10);
        for (int index = 0; index < shown; index++) {
            BattlePassScore.Entry entry = all.get(index);
            plugin.messages().send(player, "top-line",
                    "place", index + 1,
                    "player", entry.name(),
                    "points", entry.points(),
                    "completed", entry.completed());
        }
        if (all.size() > shown) {
            plugin.messages().send(player, "top-more", "rest", all.size() - shown);
        }
        int place = BattlePassScore.position(all, player.getUniqueId());
        if (place > 0) {
            BattlePassScore.Entry own = all.get(place - 1);
            plugin.messages().send(player, "top-self",
                    "place", place, "total", all.size(), "points", own.points());
        } else {
            plugin.messages().send(player, "top-none-self", "label", label);
        }
    }

    // ------------------------------------------------------------- задание --

    private void showInfo(Player player, String rawId) {
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
        BattlePassConfig.MissionDef mission = found.mission();
        UUID uuid = player.getUniqueId();
        int progress = Math.min(plugin.store().progressOf(uuid, mission.id()), mission.target());
        int percent = mission.target() <= 0 ? 0 : progress * 100 / mission.target();
        BattlePassView.Mission view = new BattlePassView.Mission(mission, progress,
                plugin.store().isClaimed(uuid, mission.id(), "FREE"),
                plugin.store().isClaimed(uuid, mission.id(), "VIP"),
                plugin.vipActive(player),
                plugin.store().isClaimed(uuid, mission.id(), "PREMIUM"),
                plugin.premiumActive(player));
        plugin.messages().send(player, "info-header",
                "desc", BattlePassScreen.described(mission));
        plugin.messages().send(player, "info-line",
                "week", found.weekIndex() + 1,
                "type", mission.type().name(),
                "id", mission.id());
        plugin.messages().send(player, "info-progress",
                "bar", plugin.screen().bar(view),
                "progress", progress, "target", mission.target(), "percent", percent);
        plugin.messages().send(player, "info-rewards",
                "reward", plugin.screen().reward(mission),
                "points", mission.points());
        if (mission.restrictedWorld()) {
            plugin.messages().send(player, "info-worlds",
                    "worlds", String.join(", ", mission.worlds()));
        }
        if (mission.restrictedPermission()) {
            plugin.messages().send(player, "info-permission",
                    "permission", mission.permission());
        }
        plugin.messages().send(player, "info-status",
                "state", plugin.screen().state(view.state()),
                "label", "battlepass");
        if (view.state() == BattlePassView.State.READY) {
            plugin.messages().send(player, "info-hint",
                    "label", "battlepass", "id", mission.id());
        } else if (view.state() == BattlePassView.State.VIP_LOCKED) {
            plugin.messages().send(player, "menu-lore-vip");
        } else if (view.state() == BattlePassView.State.PREMIUM_LOCKED) {
            plugin.messages().send(player, "menu-lore-premium");
        }
    }

    // ------------------------------------------------------------ автодополнение

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        BattlePassConfig.Season season = plugin.season();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        if (args.length != 2 || season == null) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[1].toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        if ("claim".equals(sub) && "all".startsWith(prefix)) {
            ids.add("all");
        }
        if ("claim".equals(sub) || "info".equals(sub)) {
            for (BattlePassConfig.WeekDef week : season.weeks()) {
                for (BattlePassConfig.MissionDef mission : week.missions()) {
                    if (mission.id().startsWith(prefix) && !ids.contains(mission.id())) {
                        ids.add(mission.id());
                    }
                }
            }
        }
        return ids;
    }
}
