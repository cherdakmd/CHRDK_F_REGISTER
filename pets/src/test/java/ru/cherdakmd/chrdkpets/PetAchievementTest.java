package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetAchievementTest {

    private static PetAchievement.Progress progress(int level, int kills, int fish, long minutes) {
        return new PetAchievement.Progress(level, 10, kills, fish, minutes);
    }

    @Test
    void levelMilestones() {
        assertFalse(PetAchievement.FIRST_LEVEL.unlocked(progress(1, 0, 0, 0)));
        assertTrue(PetAchievement.FIRST_LEVEL.unlocked(progress(2, 0, 0, 0)));
        assertFalse(PetAchievement.VETERAN.unlocked(progress(4, 0, 0, 0)));
        assertTrue(PetAchievement.VETERAN.unlocked(progress(5, 0, 0, 0)));
        assertFalse(PetAchievement.LEGEND.unlocked(progress(9, 0, 0, 0)));
        assertTrue(PetAchievement.LEGEND.unlocked(progress(10, 0, 0, 0)));
    }

    @Test
    void countersAndTime() {
        assertFalse(PetAchievement.HUNTER.unlocked(progress(1, 24, 0, 0)));
        assertTrue(PetAchievement.HUNTER.unlocked(progress(1, 25, 0, 0)));
        assertFalse(PetAchievement.FISHER.unlocked(progress(1, 0, 24, 0)));
        assertTrue(PetAchievement.FISHER.unlocked(progress(1, 0, 25, 0)));
        assertFalse(PetAchievement.COMPANION.unlocked(progress(1, 0, 0, 59)));
        assertTrue(PetAchievement.COMPANION.unlocked(progress(1, 0, 0, 60)));
    }

    @Test
    void nullProgressNeverUnlocks() {
        for (PetAchievement achievement : PetAchievement.values()) {
            assertFalse(achievement.unlocked(null));
        }
    }
}
