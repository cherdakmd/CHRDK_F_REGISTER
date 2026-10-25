package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Серии: продолжение в пределах окна, сброс после паузы и бонус на каждой
 * N-й находке. Это чистая арифметика, поэтому тесты не нуждаются в сервере.
 */
class TreasureStreakTest {

    private static final long WINDOW = 10_000L;

    @Test
    void firstFindStartsStreakAtOne() {
        assertEquals(1, TreasureStreak.next(0, 0L, 1_000L, WINDOW));
    }

    @Test
    void findsWithinWindowExtendStreak() {
        assertEquals(2, TreasureStreak.next(1, 1_000L, 2_000L, WINDOW));
        assertEquals(5, TreasureStreak.next(4, 1_000L, 11_000L, WINDOW));
    }

    @Test
    void pauseLongerThanWindowResetsStreak() {
        assertEquals(1, TreasureStreak.next(7, 1_000L, 11_001L, WINDOW));
        assertEquals(1, TreasureStreak.next(7, 1_000L, 60_000L, WINDOW));
    }

    @Test
    void disabledWindowAlwaysGivesFreshStreak() {
        assertEquals(1, TreasureStreak.next(9, 1_000L, 2_000L, 0L));
        assertEquals(1, TreasureStreak.next(9, 1_000L, 2_000L, -5L));
    }

    @Test
    void clockSkewDoesNotOverflowStreak() {
        assertEquals(1, TreasureStreak.next(4, 5_000L, 1_000L, WINDOW));
    }

    @Test
    void bonusFiresOnEveryNthFindOnly() {
        assertTrue(TreasureStreak.bonusDue(5, 5));
        assertTrue(TreasureStreak.bonusDue(10, 5));
        assertFalse(TreasureStreak.bonusDue(4, 5));
        assertFalse(TreasureStreak.bonusDue(5, 0));
        assertFalse(TreasureStreak.bonusDue(5, -1));
        assertFalse(TreasureStreak.bonusDue(0, 5));
    }

    @Test
    void remainingMillisCountsDownAndStopsAtZero() {
        assertEquals(9_000L, TreasureStreak.remainingMillis(1_000L, 2_000L, WINDOW));
        assertEquals(0L, TreasureStreak.remainingMillis(1_000L, 11_000L, WINDOW));
        assertEquals(0L, TreasureStreak.remainingMillis(1_000L, 12_000L, WINDOW));
        assertEquals(0L, TreasureStreak.remainingMillis(0L, 2_000L, WINDOW));
        assertEquals(0L, TreasureStreak.remainingMillis(1_000L, 2_000L, 0L));
    }
}
