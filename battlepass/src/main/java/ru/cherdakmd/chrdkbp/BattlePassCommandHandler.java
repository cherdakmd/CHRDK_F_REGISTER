package ru.cherdakmd.chrdkbp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /battlepass [claim [id|all]]} — прогресс сезона и получение наград.
 *
 * <p>Награды обеих веток за один заход выдаются одним вызовом ядра: игрок видит
 * одно сообщение об активации проходки, а не по одному на каждую награду.</p>
 */
final class BattlePassCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM")
            .withZone(ZoneId.systemDefault());

    private final BattlePassPlugin plugin;

    BattlePassCommandHandler(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "usage", "label", label);
            return true;
        }
        if (!player.hasPermission("chrdkbp.use")) {
            messages.send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            showProgress(player, label);
            return true;
        }
        if ("claim".equalsIgnoreCase(args[0])) {
            if (args.length == 1 || (args.length == 2 && "all".equalsIgnoreCase(args[1]))) {
                claimAll(player, label);
            } else if (args.length == 2) {
                claimOne(player, args[1]);
            } else {
                messages.send(player, "usage", "label", label);
            }
            return true;
        }
        messages.send(player, "usage", "label", label);
        return true;
    }

    // ------------------------------------------------------------- прогресс --

    private void showProgress(Player player, String label) {
        Messages messages = plugin.messages();
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            messages.send(player, "no-season");
            return;
        }
        long now = System.currentTimeMillis();
        int current = BattlePassConfig.currentWeek(season, now);
        // week/weeks_total передаём и в шапку: в старых messages.yml они были именно там.
        messages.send(player, "header",
                "season", described(season.name()),
                "week", Math.max(1, current), "weeks_total", season.weeks().size());
        if (current <= 0) {
            messages.send(player, "season-not-started",
                    "date", DATE.format(Instant.ofEpochMilli(season.startEpochMillis())));
            return;
        }
        messages.send(player, "subheader",
                "week", current,
                "weeks_total", season.weeks().size(),
                "next", nextWeek(season, current, now));

        long until = passUntil(player);
        boolean vipActive = until > now;
        if (vipActive) {
            messages.send(player, "vip-active",
                    "until", DATE.format(Instant.ofEpochMilli(until)));
        } else {
            messages.send(player, "vip-none");
        }

        int ready = 0;
        int vipLocked = 0;
        for (int index = 0; index < season.weeks().size(); index++) {
            if (!BattlePassConfig.isUnlocked(season, index, now)) {
                messages.send(player, "week-locked",
                        "week", index + 1,
                        "days", BattlePassConfig.daysUntilUnlock(season, index, now),
                        "date", SHORT_DATE.format(
                                Instant.ofEpochMilli(BattlePassConfig.unlockAt(season, index))));
                break;
            }
            List<BattlePassView.Mission> views = missionsOf(player, season, index, vipActive);
            int total = views.stream().mapToInt(BattlePassView.Mission::totalTracks).sum();
            int claimed = views.stream().mapToInt(BattlePassView.Mission::claimedTracks).sum();
            if (total > 0 && claimed >= total) {
                messages.send(player, "week-complete", "week", index + 1);
                continue;
            }
            messages.send(player, "week-title",
                    "week", index + 1, "claimed", claimed, "total", total);
            for (BattlePassView.Mission view : views) {
                player.sendMessage(plugin.screen().mission(label, view));
                if (view.state() == BattlePassView.State.READY) {
                    ready++;
                } else if (view.state() == BattlePassView.State.VIP_LOCKED) {
                    vipLocked++;
                }
            }
        }
        if (ready > 0) {
            messages.send(player, "footer-claimable", "count", ready, "label", label);
        } else if (vipLocked > 0) {
            messages.send(player, "footer-vip-locked", "count", vipLocked);
        } else {
            messages.send(player, "footer-idle");
        }
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

    private List<BattlePassView.Mission> missionsOf(Player player, BattlePassConfig.Season season,
                                                    int weekIndex, boolean vipActive) {
        UUID uuid = player.getUniqueId();
        List<BattlePassView.Mission> views = new ArrayList<>();
        for (BattlePassConfig.MissionDef mission : season.weeks().get(weekIndex).missions()) {
            views.add(new BattlePassView.Mission(mission,
                    plugin.store().progressOf(uuid, mission.id()),
                    plugin.store().isClaimed(uuid, mission.id(), "FREE"),
                    plugin.store().isClaimed(uuid, mission.id(), "VIP"),
                    vipActive));
        }
        return views;
    }

    // --------------------------------------------------------------- награды --

    /** Забрать всё, что готово: бесплатные ветки всем, VIP — при активной проходке. */
    private void claimAll(Player player, String label) {
        Messages messages = plugin.messages();
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            messages.send(player, "no-season");
            return;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            messages.send(player, "no-core");
            return;
        }
        long now = System.currentTimeMillis();
        boolean vipActive = api.passUntil(player.getName()) > now;
        UUID uuid = player.getUniqueId();

        int rewards = 0;
        int days = 0;
        int vipSkipped = 0;
        for (int index = 0; index < season.weeks().size(); index++) {
            if (!BattlePassConfig.isUnlocked(season, index, now)) {
                break;
            }
            for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                if (plugin.store().progressOf(uuid, mission.id()) < mission.target()) {
                    continue;
                }
                boolean freeLeft = mission.freeDays() > 0
                        && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
                boolean vipLeft = mission.vipDays() > 0
                        && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
                if (freeLeft && plugin.store().markClaimed(uuid, mission.id(), "FREE")) {
                    rewards++;
                    days += mission.freeDays();
                }
                if (!vipLeft) {
                    continue;
                }
                if (vipActive) {
                    if (plugin.store().markClaimed(uuid, mission.id(), "VIP")) {
                        rewards++;
                        days += mission.vipDays();
                    }
                } else {
                    vipSkipped++;
                }
            }
        }

        if (rewards == 0) {
            if (vipSkipped > 0) {
                messages.send(player, "claim-all-vip-only", "count", vipSkipped, "label", label);
            } else {
                messages.send(player, "claim-all-empty", "count", vipSkipped, "label", label);
            }
            return;
        }
        api.grantPass(player.getName(), days, "battlepass:claim-all");
        messages.send(player, "claim-all-summary",
                "count", rewards, "days", days,
                "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))));
        if (vipSkipped > 0) {
            messages.send(player, "claim-all-vip-skipped", "count", vipSkipped);
        }
    }

    /** Забрать награды одного задания (обе доступные ветки одним платежом). */
    private void claimOne(Player player, String rawId) {
        Messages messages = plugin.messages();
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            messages.send(player, "no-season");
            return;
        }
        BattlePassConfig.Found found = BattlePassConfig.find(season, rawId);
        if (found == null) {
            messages.send(player, "unknown", "id", rawId);
            return;
        }
        long now = System.currentTimeMillis();
        if (!BattlePassConfig.isUnlocked(season, found.weekIndex(), now)) {
            messages.send(player, "locked", "id", found.mission().id());
            return;
        }
        BattlePassConfig.MissionDef mission = found.mission();
        UUID uuid = player.getUniqueId();
        int progress = plugin.store().progressOf(uuid, mission.id());
        if (progress < mission.target()) {
            messages.send(player, "not-ready",
                    "progress", progress, "target", mission.target(),
                    "id", mission.id(), "desc", described(mission.description()));
            return;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            messages.send(player, "no-core");
            return;
        }

        boolean vipActive = api.passUntil(player.getName()) > now;
        boolean freeLeft = mission.freeDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
        boolean vipLeft = mission.vipDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
        if (!freeLeft && !vipLeft) {
            messages.send(player, "already-claimed", "desc", described(mission.description()));
            return;
        }

        int days = 0;
        if (freeLeft && plugin.store().markClaimed(uuid, mission.id(), "FREE")) {
            days += mission.freeDays();
        }
        boolean vipSkipped = vipLeft && !vipActive;
        if (vipLeft && vipActive
                && plugin.store().markClaimed(uuid, mission.id(), "VIP")) {
            days += mission.vipDays();
        }
        if (days <= 0) {
            messages.send(player, "vip-denied", "days", mission.vipDays(),
                    "id", mission.id(), "desc", described(mission.description()));
            return;
        }
        api.grantPass(player.getName(), days, "battlepass:" + mission.id());
        messages.send(player, "reward",
                "days", days,
                "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))),
                "id", mission.id(), "desc", described(mission.description()));
        if (vipSkipped) {
            messages.send(player, "vip-denied", "days", mission.vipDays(),
                    "id", mission.id(), "desc", described(mission.description()));
        }
    }

    // -------------------------------------------------------------- служебное --

    private long passUntil(Player player) {
        ChrdkRegisterApi api = plugin.coreApi();
        return api == null ? 0L : api.passUntil(player.getName());
    }

    /** Описание задания из конфига: разметка уже готова, экранировать её нельзя. */
    private static Messages.Trusted described(String text) {
        return Messages.trusted(Messages.legacyToMini(text));
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
            if ("all".startsWith(prefix)) {
                ids.add("all");
            }
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
