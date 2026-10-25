package ru.cherdakmd.chrdkbp;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Выдаёт товары каталога, не записывая их в журнал продаж: это награда, а не покупка.
     *
     * @return сколько товаров реально выдано (каталог мог измениться после рестарта)
     */
    private int grantProducts(Player player, List<String> products, String source) {
        if (products.isEmpty()) {
            return 0;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return 0;
        }
        int granted = 0;
        for (String product : products) {
            long until = api.grantEntitlement(player.getName(), product, 0, source);
            if (until <= 0) {
                plugin.getLogger().warning("Премиум-трек: товар «" + product
                        + "» не выдан игроку " + player.getName()
                        + " — товара нет в каталоге ядра или каталог выключен.");
                continue;
            }
            granted++;
        }
        return granted;
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
        boolean premiumActive = plugin.premiumActive(player);
        UUID uuid = player.getUniqueId();

        int rewards = 0;
        int days = 0;
        int vipSkipped = 0;
        int premiumSkipped = 0;
        List<String> products = new ArrayList<>();
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
                boolean premiumLeft = mission.hasPremium()
                        && !plugin.store().isClaimed(uuid, mission.id(), "PREMIUM");
                if (freeLeft && plugin.store().markClaimed(uuid, mission.id(), "FREE")) {
                    rewards++;
                    days += mission.freeDays();
                }
                if (vipLeft) {
                    if (vipActive && plugin.store().markClaimed(uuid, mission.id(), "VIP")) {
                        rewards++;
                        days += mission.vipDays();
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
                    days += mission.premiumDays();
                    for (String product : mission.premiumProducts()) {
                        if (!products.contains(product)) {
                            products.add(product);
                        }
                    }
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
        if (days > 0) {
            api.grantPass(player.getName(), days, "battlepass:claim-all");
        }
        int granted = grantProducts(player, products, "battlepass:claim-all");
        if (days > 0) {
            messages.send(player, "claim-all-summary",
                    "count", rewards, "days", days,
                    "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))));
        } else {
            messages.send(player, "claim-all-products", "count", rewards);
        }
        if (granted > 0) {
            messages.send(player, "premium-products-granted", "count", granted);
        }
        if (premiumSkipped > 0) {
            messages.send(player, "claim-all-premium-skipped", "count", premiumSkipped);
        }
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
        boolean premiumActive = plugin.premiumActive(player);
        boolean freeLeft = mission.freeDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "FREE");
        boolean vipLeft = mission.vipDays() > 0
                && !plugin.store().isClaimed(uuid, mission.id(), "VIP");
        boolean premiumLeft = mission.hasPremium()
                && !plugin.store().isClaimed(uuid, mission.id(), "PREMIUM");
        if (!freeLeft && !vipLeft && !premiumLeft) {
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
        boolean premiumSkipped = premiumLeft && !premiumActive;
        List<String> products = new ArrayList<>();
        if (premiumLeft && premiumActive
                && plugin.store().markClaimed(uuid, mission.id(), "PREMIUM")) {
            days += mission.premiumDays();
            products.addAll(mission.premiumProducts());
        }
        if (days <= 0 && products.isEmpty()) {
            if (premiumSkipped) {
                messages.send(player, "premium-denied",
                        "id", mission.id(), "desc", BattlePassScreen.described(mission));
            } else {
                messages.send(player, "vip-denied", "days", mission.vipDays(),
                        "id", mission.id(), "desc", BattlePassScreen.described(mission));
            }
            return false;
        }
        if (days > 0) {
            api.grantPass(player.getName(), days, "battlepass:" + mission.id());
        }
        int granted = grantProducts(player, products, "battlepass:" + mission.id());
        if (days > 0) {
            messages.send(player, "reward",
                    "days", days,
                    "until", DATE.format(Instant.ofEpochMilli(api.passUntil(player.getName()))),
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        } else {
            messages.send(player, "reward-products",
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        if (granted > 0) {
            messages.send(player, "premium-products-granted", "count", granted);
        }
        if (vipSkipped) {
            messages.send(player, "vip-denied", "days", mission.vipDays(),
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        if (premiumSkipped) {
            messages.send(player, "premium-denied",
                    "id", mission.id(), "desc", BattlePassScreen.described(mission));
        }
        plugin.fx().claimed(player, days);
        return true;
    }
}
