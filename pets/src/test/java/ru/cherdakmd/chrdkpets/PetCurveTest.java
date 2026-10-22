package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetCurveTest {

    private static final PetCurve.Params PARAMS = new PetCurve.Params(10, 40L, 25.0);

    @Test
    void firstLevelIsFree() {
        assertEquals(0L, PetCurve.totalForLevel(1, PARAMS));
        assertEquals(1, PetCurve.levelFor(0L, PARAMS));
    }

    @Test
    void nextLevelCostsBaseXp() {
        assertEquals(40L, PetCurve.xpForNext(1, PARAMS));
        assertEquals(1, PetCurve.levelFor(39L, PARAMS));
        assertEquals(2, PetCurve.levelFor(40L, PARAMS));
    }

    @Test
    void growthIsLinear() {
        assertEquals(65L, PetCurve.xpForNext(2, PARAMS));
        assertEquals(240L, PetCurve.xpForNext(9, PARAMS));
        assertEquals(1_260L, PetCurve.totalForLevel(10, PARAMS));
    }

    @Test
    void maxLevelSticks() {
        assertEquals(10, PetCurve.levelFor(1_260L, PARAMS));
        assertEquals(10, PetCurve.levelFor(1_000_000L, PARAMS));
        assertEquals(0L, PetCurve.progress(1_000_000L, PARAMS).need());
    }

    @Test
    void progressInsideLevel() {
        PetCurve.LevelProgress progress = PetCurve.progress(50L, PARAMS);
        assertEquals(2, progress.level());
        assertEquals(10L, progress.into());
        assertEquals(65L, progress.need());
        assertEquals(15, progress.percent());
    }

    @Test
    void maxLevelProgressIsFull() {
        PetCurve.LevelProgress progress = PetCurve.progress(1_260L, PARAMS);
        assertEquals(10, progress.level());
        assertEquals(0L, progress.into());
        assertEquals(0L, progress.need());
        assertEquals(100, progress.percent());
    }

    @Test
    void missingForLevelCountsOnlyTheRest() {
        assertEquals(1_260L, PetCurve.missingForLevel(0L, 10, PARAMS));
        assertEquals(0L, PetCurve.missingForLevel(5_000L, 10, PARAMS));
        assertEquals(65L, PetCurve.missingForLevel(40L, 3, PARAMS));
    }

    @Test
    void barHasFilledAndEmptySegments() {
        assertEquals("█████░░░░░", PetCurve.bar(50, 10));
        assertEquals("░░░░░░░░░░", PetCurve.bar(0, 10));
        assertEquals("██████████", PetCurve.bar(100, 10));
    }

    @Test
    void paramsAreClamped() {
        PetCurve.Params clamped = new PetCurve.Params(1, 0L, -5.0);
        assertEquals(2, clamped.maxLevel());
        assertEquals(1L, clamped.baseXp());
        assertEquals(0.0, clamped.growth(), 1.0e-9);
        assertTrue(PetCurve.xpForNext(1, clamped) >= 1L);
    }
}
