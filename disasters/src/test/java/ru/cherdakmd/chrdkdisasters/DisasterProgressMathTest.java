package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisasterProgressMathTest {

    @Test
    void levelsUseTriangularExperienceCurve() {
        assertEquals(0, DisasterProgressMath.cumulativeXp(1, 100));
        assertEquals(100, DisasterProgressMath.cumulativeXp(2, 100));
        assertEquals(300, DisasterProgressMath.cumulativeXp(3, 100));
        assertEquals(600, DisasterProgressMath.cumulativeXp(4, 100));
        assertEquals(4500, DisasterProgressMath.cumulativeXp(10, 100));

        assertEquals(1, DisasterProgressMath.levelForXp(0, 100, 10));
        assertEquals(1, DisasterProgressMath.levelForXp(99, 100, 10));
        assertEquals(2, DisasterProgressMath.levelForXp(100, 100, 10));
        assertEquals(2, DisasterProgressMath.levelForXp(299, 100, 10));
        assertEquals(3, DisasterProgressMath.levelForXp(300, 100, 10));
        assertEquals(10, DisasterProgressMath.levelForXp(10_000, 100, 10));
        assertEquals(1, DisasterProgressMath.levelForXp(10_000, 100, 1));

        assertEquals(100, DisasterProgressMath.xpToNext(0, 1, 100, 10));
        assertEquals(200, DisasterProgressMath.xpToNext(100, 2, 100, 10));
        assertEquals(0, DisasterProgressMath.xpToNext(4500, 10, 100, 10));
    }

    @Test
    void shardsGrowWithDisasterLevelAndSteadinessButStayCapped() {
        assertEquals(5, DisasterProgressMath.shardReward(1, 1, 5, 2, 1.5));
        assertEquals(13, DisasterProgressMath.shardReward(5, 1, 5, 2, 1.5));
        assertEquals(8, DisasterProgressMath.shardReward(1, 20, 5, 2, 1.5));
        assertEquals(0, DisasterProgressMath.shardReward(1, 1, 0, 0, 1.5));
        assertTrue(DisasterProgressMath.shardReward(5, 10, 5, 2, 1.5)
                <= Math.round(13 * 1.5));
    }

    @Test
    void dailyCapPerksAndMoscowDayBoundary() {
        assertEquals(120, DisasterProgressMath.dailyLeft(0, 120));
        assertEquals(110, DisasterProgressMath.dailyLeft(10, 120));
        assertEquals(0, DisasterProgressMath.dailyLeft(120, 120));
        assertEquals(0, DisasterProgressMath.dailyLeft(500, 120));

        assertEquals(1.0, DisasterProgressMath.effectScale(1));
        assertEquals(0.68, DisasterProgressMath.effectScale(5), 1e-9);
        assertEquals(0.4, DisasterProgressMath.effectScale(20));
        assertEquals(0.0, DisasterProgressMath.avoidChance(1));
        assertEquals(0.12, DisasterProgressMath.avoidChance(5), 1e-9);
        assertEquals(0.25, DisasterProgressMath.avoidChance(20));

        // 21:30 UTC — уже следующий день по МСК.
        long epoch = Instant.parse("2026-11-07T21:30:00Z").toEpochMilli();
        assertEquals("2026-11-08", DisasterProgressMath.dayKey(epoch));
        assertEquals("1970-01-01", DisasterProgressMath.dayKey(0));
    }
}
