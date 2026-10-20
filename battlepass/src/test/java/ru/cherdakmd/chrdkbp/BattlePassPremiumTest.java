package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Премиум-трек: доступ по сроку товара каталога и подсчёт наград сезона. */
class BattlePassPremiumTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void accessFollowsProductExpiry() {
        assertFalse(BattlePassPremium.active(true, 0L, NOW), "не куплен");
        assertFalse(BattlePassPremium.active(true, NOW, NOW), "истёк ровно сейчас");
        assertFalse(BattlePassPremium.active(true, NOW - 1, NOW), "истёк раньше");
        assertTrue(BattlePassPremium.active(true, NOW + 1, NOW), "ещё действует");
    }

    @Test
    void disabledSwitchBeatsPurchase() {
        assertFalse(BattlePassPremium.active(false, NOW + 86_400_000L, NOW),
                "админ выключил премиум-трек — проданные сроки не действуют");
    }

    @Test
    void seasonRewardsAreCounted() {
        BattlePassConfig.Season season = BattlePassConfig.season("Сезон", "2026-10-13", List.of(
                new BattlePassConfig.WeekDef(0, List.of(
                        new BattlePassConfig.MissionDef("a", "d", BattlePassConfig.MissionType.MINE,
                                "stone", 10, 1, 0, 10, List.of(), "", "", 2, List.of("trail-flame")),
                        new BattlePassConfig.MissionDef("b", "d", BattlePassConfig.MissionType.MINE,
                                "stone", 10, 1, 0),
                        new BattlePassConfig.MissionDef("c", "d", BattlePassConfig.MissionType.MINE,
                                "stone", 10, 1, 0, 10, List.of(), "", "", 0, List.of("nick-color"))))));

        assertTrue(BattlePassPremium.anyRewards(season));
        assertEquals(2, BattlePassPremium.missionsWithRewards(season));
    }

    @Test
    void seasonWithoutPremiumIsQuiet() {
        BattlePassConfig.Season season = BattlePassConfig.season("Сезон", "2026-10-13", List.of(
                new BattlePassConfig.WeekDef(0, List.of(
                        new BattlePassConfig.MissionDef("a", "d", BattlePassConfig.MissionType.MINE,
                                "stone", 10, 1, 1)))));
        assertFalse(BattlePassPremium.anyRewards(season));
        assertEquals(0, BattlePassPremium.missionsWithRewards(season));
        assertFalse(BattlePassPremium.anyRewards(null));
        assertEquals(0, BattlePassPremium.missionsWithRewards(null));
    }
}
