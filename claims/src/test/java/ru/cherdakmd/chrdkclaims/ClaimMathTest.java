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
        assertEquals(1, ClaimMath.overdueDays(base, base), "в момент дедлайна сутка подлежит списанию");
        assertEquals(1, ClaimMath.overdueDays(base, base + 1), "первая просроченная сутка");
        assertEquals(1, ClaimMath.overdueDays(base, base + day - 1));
        assertEquals(2, ClaimMath.overdueDays(base, base + day));
        assertEquals(10, ClaimMath.overdueDays(base, base + day * 9 + 5));
    }

    @Test
    void unpaidDayBecomesDebt() {
        assertEquals(new ClaimMath.RentStep(0, 1, false),
                ClaimMath.settleRentDay(0, 1, 0, 3));
        assertEquals(new ClaimMath.RentStep(0, 4, true),
                ClaimMath.settleRentDay(0, 1, 3, 3), "четвёртые сутки долга — освобождение");
        assertEquals(new ClaimMath.RentStep(0, 4, false),
                ClaimMath.settleRentDay(0, 1, 3, 5), "при большей льготе чанк ещё жив");
    }

    @Test
    void oldDebtIsPaidBeforeTheNewDay() {
        // Казна 3, долг 2: две сутки долга закрываются, третья — текущая.
        assertEquals(new ClaimMath.RentStep(3, 0, false),
                ClaimMath.settleRentDay(3, 1, 2, 3));
        // Казна 2, долг 2: долг закрыт, но на текущие сутки денег уже нет.
        assertEquals(new ClaimMath.RentStep(2, 1, false),
                ClaimMath.settleRentDay(2, 1, 2, 3));
        // Казна 1, долг 2: одна сутка долга закрыта, вторая осталась + новая.
        assertEquals(new ClaimMath.RentStep(1, 2, false),
                ClaimMath.settleRentDay(1, 1, 2, 3));
        // Без долга списывается ровно одна сутки.
        assertEquals(new ClaimMath.RentStep(1, 0, false),
                ClaimMath.settleRentDay(5, 1, 0, 3));
    }

    @Test
    void disabledRentDoesNothing() {
        assertEquals(new ClaimMath.RentStep(0, 0, false),
                ClaimMath.settleRentDay(50, 0, 0, 3));
        assertEquals(new ClaimMath.RentStep(0, 2, false),
                ClaimMath.settleRentDay(50, 0, 2, 3), "долг не растёт, но и не прощается");
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

    @Test
    void worldMultiplierScalesAndNeverZeroesThePrice() {
        assertEquals(16, ClaimMath.scaled(16, 1.0));
        assertEquals(8, ClaimMath.scaled(16, 0.5));
        assertEquals(24, ClaimMath.scaled(16, 1.5), "округление к ближайшему");
        assertEquals(32, ClaimMath.scaled(16, 2.0));
        assertEquals(1, ClaimMath.scaled(1, 0.5), "цена не может стать нулевой");
        assertEquals(0, ClaimMath.scaled(0, 2.0), "нулевая база остаётся нулевой");
        assertEquals(16, ClaimMath.scaled(16, 0), "множитель 0 не выключает экономику");
        assertEquals(16, ClaimMath.scaled(16, -3));
        assertEquals(16, ClaimMath.scaled(16, Double.NaN));
        assertEquals(16, ClaimMath.scaled(16, Double.POSITIVE_INFINITY));
        assertEquals(Integer.MAX_VALUE, ClaimMath.scaled(Integer.MAX_VALUE, 10.0),
                "переполнение зажимается");
    }

    @Test
    void daysUntilRoundsDownAndNeverNegative() {
        long now = 5_000_000_000L;
        assertEquals(0, ClaimMath.daysUntil(now, now));
        assertEquals(0, ClaimMath.daysUntil(now + 1, now));
        assertEquals(0, ClaimMath.daysUntil(now - ClaimMath.DAY_MILLIS, now), "просрочено");
        assertEquals(1, ClaimMath.daysUntil(now + ClaimMath.DAY_MILLIS, now));
        assertEquals(2, ClaimMath.daysUntil(now + ClaimMath.DAY_MILLIS * 2 + 5, now));
    }
}
