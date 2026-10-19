package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Состояния заданий и полоса прогресса — без Bukkit. */
class BattlePassViewTest {

    private static final BattlePassConfig.MissionDef ORE = new BattlePassConfig.MissionDef(
            "mine_diamonds", "<gray>Добыть алмазную руду</gray>",
            BattlePassConfig.MissionType.MINE, "diamond_ore", 16, 1, 2);

    private static BattlePassConfig.MissionDef vipOnly() {
        return new BattlePassConfig.MissionDef("hunt_zombies", "<gray>Победить зомби</gray>",
                BattlePassConfig.MissionType.KILL, "zombie", 40, 0, 1);
    }

    @Test
    void stateFollowsProgressAndClaims() {
        assertEquals(BattlePassView.State.PROGRESS,
                new BattlePassView.Mission(ORE, 15, false, false, false).state());
        assertEquals(BattlePassView.State.READY,
                new BattlePassView.Mission(ORE, 16, false, false, false).state());
        assertEquals(BattlePassView.State.READY,
                new BattlePassView.Mission(ORE, 16, true, false, true).state(),
                "бесплатная забрана, но VIP с проходкой ещё доступна");
        assertEquals(BattlePassView.State.VIP_LOCKED,
                new BattlePassView.Mission(ORE, 16, true, false, false).state());
        assertEquals(BattlePassView.State.CLAIMED,
                new BattlePassView.Mission(ORE, 16, true, true, false).state());
        assertEquals(BattlePassView.State.READY,
                new BattlePassView.Mission(ORE, 16, false, true, false).state(),
                "VIP забрана, бесплатная ещё нет");
        assertEquals(BattlePassView.State.PROGRESS,
                new BattlePassView.Mission(ORE, 0, false, false, false).state());
    }

    @Test
    void vipOnlyWithoutPassIsLockedNotReady() {
        assertEquals(BattlePassView.State.VIP_LOCKED,
                new BattlePassView.Mission(vipOnly(), 40, false, false, false).state());
        assertEquals(BattlePassView.State.READY,
                new BattlePassView.Mission(vipOnly(), 40, false, false, true).state());
        assertEquals(BattlePassView.State.CLAIMED,
                new BattlePassView.Mission(vipOnly(), 40, false, true, false).state());
    }

    @Test
    void claimedTracksCountOnlyConfiguredRewards() {
        BattlePassView.Mission both = new BattlePassView.Mission(ORE, 0, true, false, false);
        assertEquals(2, both.totalTracks());
        assertEquals(1, both.claimedTracks());
        BattlePassView.Mission vip = new BattlePassView.Mission(vipOnly(), 0, false, false, false);
        assertEquals(1, vip.totalTracks());
        assertEquals(0, vip.claimedTracks());
    }

    @Test
    void shownProgressIsCappedByTarget() {
        assertEquals(16, new BattlePassView.Mission(ORE, 99, false, false, false).shownProgress());
        assertEquals(0, new BattlePassView.Mission(ORE, -5, false, false, false).shownProgress());
    }

    @Test
    void barFillsEvenlyAndNeverOverflows() {
        assertEquals(0, BattlePassView.filledSegments(0, 16, 8));
        assertEquals(1, BattlePassView.filledSegments(1, 16, 8), "любой прогресс виден");
        assertEquals(4, BattlePassView.filledSegments(8, 16, 8));
        assertEquals(8, BattlePassView.filledSegments(16, 16, 8));
        assertEquals(8, BattlePassView.filledSegments(50, 16, 8), "цель перекрыта — полоса полная");
        assertEquals(0, BattlePassView.filledSegments(5, 0, 8), "нулевая цель не делит на ноль");
        assertEquals(0, BattlePassView.filledSegments(5, 10, 0));
    }
}
