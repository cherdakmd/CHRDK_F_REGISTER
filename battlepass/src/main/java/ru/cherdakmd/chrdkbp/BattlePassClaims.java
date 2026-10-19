package ru.cherdakmd.chrdkbp;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Выдача наград за задания. Один и тот же код используют команда
 * {@code /battlepass claim} и клик в меню, поэтому правила не расходятся.
 *
 * <p>Дни обеих веток за один заход складываются и выдаются одним вызовом ядра:
 * игрок получает одно поздравление вместо пачки одинаковых сообщений.</p>
 */
final class BattlePassClaims {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());

    private final BattlePassPlugin plugin;

    BattlePassClaims(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    /** Забрать все готовые награды. Возвращает число выданных наград (0 — нечего). */
    int claimAll(Player player) {
        Messages messages = plugin.messages();
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            messages.send(player, "no-season");
            return 0;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            messages.send(player, "no-core");
            return 0;
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
                messages.send(player, "claim-all-vip-only", "count", vipSkipped);
            } else {
                messages.send(player, "claim-all-empty");
            }
            return 0;
        }
        api.grantPass(player.getName(), days, "battlepass:claim-all");
        messages.send(player, "claim-all-summary",
                "count", rewards, "days", days,
                "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))));
        if (vipSkipped > 0) {
            messages.send(player, "claim-all-vip-skipped", "count", vipSkipped);
        }
        plugin.fx().claimed(player, days);
        return rewards;
    }

    /** Забрать награды одного задания. {@code true} — если что-то выдано. */
    boolean claimOne(Player player, String rawId) {
        Messages messages = plugin.messages();
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            messages.send(player, "no-season");
            return false;
        }
        BattlePassConfig.Found found = BattlePassConfig.find(season, rawId);
        if (found == null) {
            messages.send(player, "unknown", "id", rawId);
            return false;
        }
        long now = System.currentTimeMillis();
        if (!BattlePassConfig.isUnlocked(season, found.weekIndex(), now)) {
            messages.send(player, "locked", "id", found.mission().id());
            return false;
        }
        BattlePassConfig.MissionDef mission = found.mission();
        UUID uuid = player.getUniqueId();
        int progress = plugin.store().progressOf(uuid, mission.id());
        if (progress < mission.target()) {
            messages.send(player, "not-ready",
                    "progress", progress, "target", mission.target(),
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
            return false;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            messages.send(player, "no-core");
            return false;
        }

        boolean vipActive = api.passUntil(player.getName()) > now;
        boolean freeLeft = mission.freeDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
        boolean vipLeft = mission.vipDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
        if (!freeLeft && !vipLeft) {
            messages.send(player, "already-claimed",
                    "desc", BattlePassScreen.described(mission));
            return false;
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
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
            return false;
        }
        api.grantPass(player.getName(), days, "battlepass:" + mission.id());
        messages.send(player, "reward",
                "days", days,
                "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))),
                "id", mission.id(), "desc", BattlePassScreen.described(mission));
        if (vipSkipped) {
            messages.send(player, "vip-denied", "days", mission.vipDays(),
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        plugin.fx().claimed(player, days);
        return true;
    }
}
