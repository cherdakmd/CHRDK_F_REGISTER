package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import static org.junit.jupiter.api.Assertions.*;

class SkillXpPolicyTest {
    @Test void progressionAndPaidRankIncreaseButCapDailyUses() {
        assertEquals(2, SkillXpPolicy.dailyUses(10, 0, RankBonus.NONE));
        assertEquals(4, SkillXpPolicy.dailyUses(400, 20, RankBonus.NONE));
        assertEquals(5, SkillXpPolicy.dailyUses(10, 0, new RankBonus(3, 20, 0, 0)));
        assertEquals(8, SkillXpPolicy.dailyUses(10_000, 10_000, new RankBonus(5, 50, 25, 10)));
    }

    @Test void potionXpHasBoundedPrestigeAndRankBonuses() {
        assertEquals(250, SkillXpPolicy.xp(0, RankBonus.NONE));
        assertEquals(300, SkillXpPolicy.xp(20, RankBonus.NONE));
        assertEquals(375, SkillXpPolicy.xp(0, new RankBonus(5, 50, 25, 10)));
        assertEquals(563, SkillXpPolicy.xp(200, new RankBonus(5, 50, 25, 10)));
        assertTrue(SkillXpPolicy.dropChance(RankBonus.NONE) < SkillXpPolicy.dropChance(new RankBonus(5, 50, 25, 10)));
        assertTrue(SkillXpPolicy.dropChance(new RankBonus(5, 50, 25, 10)) <= 0.01);
    }
}
