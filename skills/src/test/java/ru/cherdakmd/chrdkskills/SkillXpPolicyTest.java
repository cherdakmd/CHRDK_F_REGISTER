package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import static org.junit.jupiter.api.Assertions.*;

class SkillXpPolicyTest {
    @Test void progressionAndPaidRankIncreaseButCapDailyUses() {
        assertEquals(2, SkillXpPolicy.dailyUses(10, 0, RankBonus.NONE));
        assertEquals(6, SkillXpPolicy.dailyUses(400, 20, RankBonus.NONE));
        assertEquals(5, SkillXpPolicy.dailyUses(10, 0, new RankBonus(3, 20, 0, 0)));
        assertEquals(8, SkillXpPolicy.dailyUses(10_000, 10_000, new RankBonus(5, 50, 25, 10)));
    }

    @Test void potionXpHasBoundedPrestigeAndRankBonuses() {
        assertEquals(250, SkillXpPolicy.xp(0, RankBonus.NONE));
        assertEquals(300, SkillXpPolicy.xp(20, RankBonus.NONE));
        assertEquals(375, SkillXpPolicy.xp(0, new RankBonus(5, 50, 25, 10)));
        assertEquals(563, SkillXpPolicy.xp(200, new RankBonus(5, 50, 25, 10)));
    }

    @Test void dropChanceUsesStreakTierAndRankWithHardCap() {
        assertEquals(0.0022, SkillXpPolicy.dropChance(0, 1.0, RankBonus.NONE), 1e-9);
        assertEquals(0.0022, SkillXpPolicy.dropChance(-5, 1.0, RankBonus.NONE), 1e-9);
        assertEquals(0.0033, SkillXpPolicy.dropChance(0, 1.0, new RankBonus(5, 50, 25, 10)), 1e-9);
        assertEquals(0.0033, SkillXpPolicy.dropChance(0, 1.5, RankBonus.NONE), 1e-9);
        // Серия и тир растягивают шанс, но потолок 3% непреодолим.
        assertEquals(0.03, SkillXpPolicy.dropChance(100, 1.0, RankBonus.NONE), 1e-9);
        assertEquals(0.03, SkillXpPolicy.dropChance(28, 2.0, new RankBonus(5, 50, 25, 10)), 1e-9);
        assertTrue(SkillXpPolicy.dropChance(0, 1.0, new RankBonus(5, 50, 25, 10))
                > SkillXpPolicy.dropChance(0, 1.0, RankBonus.NONE));
    }

    @Test void pityGuaranteesDropWithoutInfiniteWaits() {
        assertFalse(SkillXpPolicy.pityReached(144));
        assertTrue(SkillXpPolicy.pityReached(SkillXpPolicy.PITY_KILLS));
        assertEquals(145, SkillXpPolicy.PITY_KILLS);
    }
}
