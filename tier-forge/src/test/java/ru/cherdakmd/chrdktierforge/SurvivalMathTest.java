package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Порог «Последнего рубежа» с учётом поглощения (1.4.98). */
class SurvivalMathTest {

    @Test
    void absorptionEatsItsShareOfDamage() {
        assertEquals(2.0, SurvivalMath.healthDamage(10.0, 8.0), 1e-9);
        assertEquals(0.0, SurvivalMath.healthDamage(5.0, 9.0), 1e-9, "щит больше удара");
        assertEquals(0.0, SurvivalMath.healthDamage(-3.0, 2.0), 1e-9, "отрицательный урон не лечит");
        assertEquals(4.0, SurvivalMath.healthDamage(4.0, -1.0), 1e-9, "кривое поглощение = 0");
    }

    @Test
    void lethalUsesHealthAfterAbsorption() {
        assertTrue(SurvivalMath.isLethal(6.0, 0.0, 6.0), "ровно в ноль — смертельно");
        assertTrue(SurvivalMath.isLethal(6.0, 2.0, 8.0));
        assertFalse(SurvivalMath.isLethal(6.0, 8.0, 12.0), "щиты съедают удар");
        assertFalse(SurvivalMath.isLethal(6.0, 0.0, 5.9));
    }

    @Test
    void zeroOrNegativeHealthNeverTriggers() {
        assertFalse(SurvivalMath.isLethal(0.0, 0.0, 100.0));
        assertFalse(SurvivalMath.isLethal(-2.0, 0.0, 100.0));
    }

    @Test
    void exactlyAbsorbedHitIsNotLethal() {
        assertFalse(SurvivalMath.isLethal(1.0, 20.0, 20.0), "полностью поглощённый удар");
        assertTrue(SurvivalMath.isLethal(1.0, 19.0, 20.0), "лишний 1 урона добивает");
    }
}
