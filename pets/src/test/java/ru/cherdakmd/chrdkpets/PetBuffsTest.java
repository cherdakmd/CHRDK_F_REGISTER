package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBuffsTest {

    private static final PetBuffs.Settings WOLF = new PetBuffs.Settings(
            2.0, 18.0, 0.25, 2.25, 0.4, 3.6, 0.3, 2.7, 0.0, 0.0);
    private static final PetBuffs.Settings CAT = new PetBuffs.Settings(
            1.0, 9.0, 0.0, 0.0, 0.35, 3.15, 0.0, 0.0, 0.1, 0.9);

    @Test
    void levelOneGivesNothing() {
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(WOLF, 1));
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(CAT, 0));
    }

    @Test
    void wolfAtMaxLevel() {
        PetBuffs.Stats stats = PetBuffs.stats(WOLF, 10);
        assertEquals(18.0, stats.health(), 1.0e-9);
        assertEquals(2.25, stats.damage(), 1.0e-9);
        assertEquals(0.036, stats.speedScalar(), 1.0e-9);
        assertEquals(0.027, stats.ownerSpeedScalar(), 1.0e-9);
        assertEquals(0.0, stats.ownerLuck(), 1.0e-9);
    }

    @Test
    void catAtMaxLevel() {
        PetBuffs.Stats stats = PetBuffs.stats(CAT, 10);
        assertEquals(9.0, stats.health(), 1.0e-9);
        assertEquals(0.0, stats.damage(), 1.0e-9);
        assertEquals(0.0315, stats.speedScalar(), 1.0e-9);
        assertEquals(0.0, stats.ownerSpeedScalar(), 1.0e-9);
        assertEquals(0.9, stats.ownerLuck(), 1.0e-9);
    }

    @Test
    void capsHoldBeyondMaxLevel() {
        PetBuffs.Stats stats = PetBuffs.stats(WOLF, 1000);
        assertEquals(18.0, stats.health(), 1.0e-9);
        assertEquals(2.25, stats.damage(), 1.0e-9);
        assertEquals(0.036, stats.speedScalar(), 1.0e-9);
        assertEquals(0.027, stats.ownerSpeedScalar(), 1.0e-9);
    }

    @Test
    void partialLevelIsLinear() {
        PetBuffs.Stats stats = PetBuffs.stats(WOLF, 5);
        assertEquals(8.0, stats.health(), 1.0e-9);
        assertEquals(1.0, stats.damage(), 1.0e-9);
        assertEquals(0.016, stats.speedScalar(), 1.0e-9);
        assertEquals(0.012, stats.ownerSpeedScalar(), 1.0e-9);
    }

    @Test
    void negativeSettingsBecomeZero() {
        PetBuffs.Settings broken = new PetBuffs.Settings(
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
        assertEquals(0.0, broken.healthPerLevel(), 1.0e-9);
        assertEquals(0.0, broken.maxOwnerLuck(), 1.0e-9);
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(broken, 10));
    }

    @Test
    void noneSettingsAndNullAreSafe() {
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(PetBuffs.Settings.NONE, 10));
        assertEquals(PetBuffs.Stats.NONE, PetBuffs.stats(null, 10));
    }

    @Test
    void ownerBonusIsDetectable() {
        assertTrue(PetBuffs.stats(WOLF, 10).hasOwnerBonus());
        assertTrue(PetBuffs.stats(CAT, 10).hasOwnerBonus());
        assertFalse(PetBuffs.stats(WOLF, 1).hasOwnerBonus());
    }

    @Test
    void statsAreRounded() {
        PetBuffs.Settings odd = new PetBuffs.Settings(
                0.3333333, 10, 0.1111111, 10, 0.7777777, 100, 0.1234567, 100, 0.1111111, 10);
        PetBuffs.Stats stats = PetBuffs.stats(odd, 3);
        assertEquals(0.667, stats.health(), 1.0e-9);
        assertEquals(0.222, stats.damage(), 1.0e-9);
    }
}
