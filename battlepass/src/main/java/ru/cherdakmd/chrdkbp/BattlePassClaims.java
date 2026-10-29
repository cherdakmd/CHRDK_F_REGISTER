package ru.cherdakmd.chrdkbp;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.UUID;

/**
 * Выдача наград за задания. Один и тот же код используют команда
 * {@code /battlepass claim} и клик в меню, поэтому правила не расходятся.
 *
 * <p>Дни обеих веток за один заход складываются и выдаются одним вызовом ядра:
 * игрок получает одно поздравление вместо пачки одинаковых сообщений.</p>
 */
final class BattlePassClaims {

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
        boolean vipActive = plugin.vipActive(player);
        boolean premiumActive = plugin.premiumActive(player);
        UUID uuid = player.getUniqueId();

        int rewards = 0;
        int hours = 0;
        int vipSkipped = 0;
        int premiumSkipped = 0;
        for (int index = 0; index < season.weeks().size(); index++) {
            if (!BattlePassConfig.isUnlocked(season, index, now)) {
                break;
            }
            for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                if (plugin.store().progressOf(uuid, mission.id()) < mission.target()) {
                    continue;
                }
                boolean freeLeft = mission.freeHours() > 0
                        && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
                boolean vipLeft = mission.vipHours() > 0
                        && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
                boolean premiumLeft = mission.hasPremium()
                        && !plugin.store().isClaimed(uuid, mission.id(), "PREMIUM");
                if (freeLeft && plugin.store().markClaimed(uuid, mission.id(), "FREE")) {
                    rewards++;
                    hours += mission.freeHours();
                }
                if (vipLeft) {
                    if (vipActive && plugin.store().markClaimed(uuid, mission.id(), "VIP")) {
                        rewards++;
                        hours += mission.vipHours();
                    } else if (!vipActive) {
                        vipSkipped++;
                    }
                }
                if (!premiumLeft) {
                    continue;
                }
                if (!premiumActive) {
                    premiumSkipped++;
                } else if (plugin.store().markClaimed(uuid, mission.id(), "PREMIUM")) {
                    rewards++;
                    hours += mission.premiumHours();

                }
            }
        }

        if (rewards == 0) {
            if (premiumSkipped > 0) {
                messages.send(player, "claim-all-premium-only", "count", premiumSkipped);
            } else if (vipSkipped > 0) {
                messages.send(player, "claim-all-vip-only", "count", vipSkipped);
            } else {
                messages.send(player, "claim-all-empty");
            }
            return 0;
        }
        if (hours > 0) {
            api.grantRewardBoostHours(player.getName(), hours, "battlepass:claim-all");
        }
        messages.send(player, "claim-all-summary", "count", rewards, "hours", hours);
        if (premiumSkipped > 0) {
            messages.send(player, "claim-all-premium-skipped", "count", premiumSkipped);
        }
        if (vipSkipped > 0) {
            messages.send(player, "claim-all-vip-skipped", "count", vipSkipped);
        }
        plugin.fx().claimed(player, hours);
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

        boolean vipActive = plugin.vipActive(player);
        boolean premiumActive = plugin.premiumActive(player);
        boolean freeLeft = mission.freeHours() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
        boolean vipLeft = mission.vipHours() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
        boolean premiumLeft = mission.hasPremium()
                && !plugin.store().isClaimed(uuid, mission.id(), "PREMIUM");
        if (!freeLeft && !vipLeft && !premiumLeft) {
            messages.send(player, "already-claimed",
                    "desc", BattlePassScreen.described(mission));
            return false;
        }

        int hours = 0;
        if (freeLeft && plugin.store().markClaimed(uuid, mission.id(), "FREE")) {
            hours += mission.freeHours();
        }
        boolean vipSkipped = vipLeft && !vipActive;
        if (vipLeft && vipActive
                && plugin.store().markClaimed(uuid, mission.id(), "VIP")) {
            hours += mission.vipHours();
        }
        boolean premiumSkipped = premiumLeft && !premiumActive;
        if (premiumLeft && premiumActive
                && plugin.store().markClaimed(uuid, mission.id(), "PREMIUM")) {
            hours += mission.premiumHours();
        }
        if (hours <= 0) {
            if (premiumSkipped) {
                messages.send(player, "premium-denied",
                        "id", mission.id(), "desc", BattlePassScreen.described(mission));
            } else {
                messages.send(player, "vip-denied", "hours", mission.vipHours(),
                        "id", mission.id(), "desc", BattlePassScreen.described(mission));
            }
            return false;
        }
        if (hours > 0) {
            api.grantRewardBoostHours(player.getName(), hours, "battlepass:" + mission.id());
        }
        messages.send(player, "reward", "hours", hours,
                "id", mission.id(), "desc", BattlePassScreen.described(mission));
        if (vipSkipped) {
            messages.send(player, "vip-denied", "hours", mission.vipHours(),
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        if (premiumSkipped) {
            messages.send(player, "premium-denied",
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        plugin.fx().claimed(player, hours);
        return true;
    }
}
