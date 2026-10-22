package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBuffsTest {

    private static final PetBuffs.Settings WOLF =
            new PetBuffs.Settings(2.0, 18.0, 0.25, 2.25, 0.4, 3.6, 0.3, 2.7, 0.0, 0.0);
    private static final PetBuffs.Settings CAT =
            new PetBuffs.Settings(1.0, 9.0, 0.0, 0.0, 0.35, 3.15, 0.0, 0.0, 0.1, 0.9);

    @Test
    void levelOneIsNeutral() {
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(WOLF, 1));
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(CAT, 1));
    }

    @Test
    void levelBelowOneIsNeutral() {
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(WOLF, 0));
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(WOLF, -7));
    }

    @Test
    void wolfReachesDocumentedCeilingAtLevelTen() {
        PetBuffs.Stats stats = PetBuffs.stats(WOLF, 10);
        assertEquals(18.0, stats.health(), 1.0e-9);
        assertEquals(2.25, stats.damage(), 1.0e-9);
        assertEquals(0.036, stats.speedScalar(), 1.0e-9);
        assertEquals(0.027, stats.ownerSpeedScalar(), 1.0e-9);
        assertEquals(0.0, stats.ownerLuck(), 1.0e-9);
        assertTrue(stats.hasOwnerBonus());
    }

    @Test
    void catReachesDocumentedCeilingAtLevelTen() {
        PetBuffs.Stats stats = PetBuffs.stats(CAT, 10);
        assertEquals(9.0, stats.health(), 1.0e-9);
        assertEquals(0.0, stats.damage(), 1.0e-9);
        assertEquals(0.0315, stats.speedScalar(), 1.0e-9);
        assertEquals(0.0, stats.ownerSpeedScalar(), 1.0e-9);
        assertEquals(0.9, stats.ownerLuck(), 1.0e-9);
        assertTrue(stats.hasOwnerBonus());
    }

    @Test
    void growthIsLinearBetweenLevels() {
        PetBuffs.Stats levelFive = PetBuffs.stats(WOLF, 5);
        assertEquals(8.0, levelFive.health(), 1.0e-9);
        assertEquals(1.0, levelFive.damage(), 1.0e-9);
        assertEquals(0.016, levelFive.speedScalar(), 1.0e-9);
        assertEquals(0.012, levelFive.ownerSpeedScalar(), 1.0e-9);
    }

    @Test
    void capsHoldEvenWithHugeSettings() {
        PetBuffs.Settings huge = new PetBuffs.Settings(
                100.0, 18.0, 100.0, 2.25, 100.0, 3.6, 100.0, 2.7, 100.0, 0.9);
        PetBuffs.Stats stats = PetBuffs.stats(huge, 100);
        assertEquals(18.0, stats.health(), 1.0e-9);
        assertEquals(2.25, stats.damage(), 1.0e-9);
        assertEquals(0.036, stats.speedScalar(), 1.0e-9);
        assertEquals(0.027, stats.ownerSpeedScalar(), 1.0e-9);
        assertEquals(0.9, stats.ownerLuck(), 1.0e-9);
    }

    @Test
    void negativeSettingsBecomeZero() {
        PetBuffs.Settings negative = new PetBuffs.Settings(
                -2.0, -18.0, -0.25, -2.25, -0.4, -3.6, -0.3, -2.7, -0.1, -0.9);
        PetBuffs.Stats stats = PetBuffs.stats(negative, 10);
        assertEquals(PetBuffs.Stats.NONE, stats);
        assertFalse(stats.hasOwnerBonus());
    }

    @Test
    void nullSettingsMeanNoBuffs() {
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(null, 10));
    }
}
