package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatMathTest {

    @Test
    void absorptionShieldsReduceIncomingDamage() {
        assertEquals(6.0, CombatMath.healthDamage(10.0, 4.0), 1e-9);
        assertEquals(0.0, CombatMath.healthDamage(10.0, 12.0), 1e-9);
        assertEquals(10.0, CombatMath.healthDamage(10.0, -3.0), 1e-9, "отрицательный щит — это ноль");
    }

    @Test
    void lethalHitRespectsShields() {
        assertTrue(CombatMath.isLethal(4.0, 8.0, 0.0));
        assertFalse(CombatMath.isLethal(4.0, 8.0, 5.0), "щит съел половину — удар не смертелен");
        assertTrue(CombatMath.isLethal(2.0, 8.0, 6.0), "ровно смертельный остаток");
        assertFalse(CombatMath.isLethal(0.0, 4.0, 0.0), "мёртвого не спасаем");
        assertFalse(CombatMath.isLethal(-1.0, 4.0, 0.0));
    }

    @Test
    void reviveHealthIsHalfButNeverBelowOneHeart() {
        assertEquals(10.0, CombatMath.reviveHealth(20.0, 0.5), 1e-9);
        assertEquals(1.0, CombatMath.reviveHealth(1.0, 0.5), 1e-9);
        assertEquals(20.0, CombatMath.reviveHealth(0.0, 0.5), 1e-9, "нулевой максимум — 20");
        assertEquals(20.0, CombatMath.reviveHealth(-5.0, 0.5), 1e-9);
        assertEquals(10.0, CombatMath.reviveHealth(20.0, 2.0), 1e-9, "доля clamp 1.0");
    }

    @Test
    void zeroHealthIsNeverLethal() {
        assertFalse(CombatMath.isLethal(0.0, 0.0, 0.0));
        assertFalse(CombatMath.isLethal(0.0, 100.0, 0.0));
        assertTrue(CombatMath.isLethal(15.0, 100.0, 0.0));
    }
}
