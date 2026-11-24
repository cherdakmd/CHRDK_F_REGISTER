package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingXpBonusTest {
    @Test void fractionalBonusIsUnbiasedAcrossAllRolls() {
        long total = 0;
        for (int roll = 0; roll < 100; roll++) total += SkillService.fishingBonus(8, 15, 100, roll);
        assertEquals(920, total); // 8 XP plus an average of 1.2 XP.
    }

    @Test void bonusNeverExceedsTheActionCapOrOverflows() {
        assertEquals(10, SkillService.fishingBonus(10, 20, 10, 0));
        assertEquals(11, SkillService.fishingBonus(10, 20, 11, 0));
        assertEquals(Long.MAX_VALUE, SkillService.fishingBonus(Long.MAX_VALUE, 20, Long.MAX_VALUE, 0));
        assertEquals(8, SkillService.fishingBonus(8, 0, 100, 0));
    }

    @Test void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkillService.fishingBonus(-1, 10, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> SkillService.fishingBonus(10, 21, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> SkillService.fishingBonus(10, 10, 9, 0));
        assertThrows(IllegalArgumentException.class, () -> SkillService.fishingBonus(10, 10, 100, 100));
    }
}
