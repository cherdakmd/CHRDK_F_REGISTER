package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClaimMathTest {

    @Test
    void overdueCountsOnlyWholeDays() {
        long day = ClaimMath.DAY_MILLIS;
        long base = 1_000_000_000L;
        assertEquals(0, ClaimMath.overdueDays(base, base - 1), "будущее не должно");
        assertEquals(0, ClaimMath.overdueDays(base, base), "ровно в срок — ещё не просрочено");
        assertEquals(1, ClaimMath.overdueDays(base, base + 1), "первая просроченная сутка");
        assertEquals(1, ClaimMath.overdueDays(base, base + day - 1));
        assertEquals(2, ClaimMath.overdueDays(base, base + day));
        assertEquals(10, ClaimMath.overdueDays(base, base + day * 9 + 5));
    }

    @Test
    void saleTaxCeilsAndHasMinimum() {
        assertEquals(0, ClaimMath.saleTax(0, 10));
        assertEquals(0, ClaimMath.saleTax(100, 0));
        assertEquals(1, ClaimMath.saleTax(5, 10), "минимум 1 при ненулевой ставке");
        assertEquals(2, ClaimMath.saleTax(15, 10), "округление вверх: 1.5 → 2");
        assertEquals(10, ClaimMath.saleTax(100, 10));
    }

    @Test
    void sellbackFloors() {
        assertEquals(0, ClaimMath.sellbackReward(16, 0));
        assertEquals(8, ClaimMath.sellbackReward(16, 50));
        assertEquals(9, ClaimMath.sellbackReward(15, 60), "9.0 ровно");
        assertEquals(7, ClaimMath.sellbackReward(15, 50), "7.5 → 7");
    }

    @Test
    void passBonusAddsToLimit() {
        assertEquals(24, ClaimMath.effectiveLimit(24, 8, false));
        assertEquals(32, ClaimMath.effectiveLimit(24, 8, true));
    }

    @Test
    void chunkKeyKeepsNegativeCoordinatesApart() {
        assertEquals(ClaimMath.chunkKey(3, -7), ClaimMath.chunkKey(3, -7));
        assertNotEquals(ClaimMath.chunkKey(3, -7), ClaimMath.chunkKey(3, 7));
        assertNotEquals(ClaimMath.chunkKey(3, -7), ClaimMath.chunkKey(-3, -7));
        assertNotEquals(ClaimMath.chunkKey(-1, -1), ClaimMath.chunkKey(0, -1));
    }
}
