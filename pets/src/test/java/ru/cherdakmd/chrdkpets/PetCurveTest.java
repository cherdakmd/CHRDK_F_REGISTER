package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetCurveTest {

    private static final PetCurve.Params PARAMS = new PetCurve.Params(10, 40L, 25.0);

    @Test
    void zeroXpIsFirstLevel() {
        assertEquals(1, PetCurve.levelFor(0L, PARAMS));
        assertEquals(1, PetCurve.levelFor(-500L, PARAMS));
    }

    @Test
    void firstLevelCostsBaseXp() {
        assertEquals(40L, PetCurve.xpForNext(1, PARAMS));
        assertEquals(65L, PetCurve.xpForNext(2, PARAMS));
        assertEquals(240L, PetCurve.xpForNext(9, PARAMS));
    }

    @Test
    void totalPathToOneLevelTenIs1260() {
        assertEquals(0L, PetCurve.totalForLevel(1, PARAMS));
        assertEquals(40L, PetCurve.totalForLevel(2, PARAMS));
        assertEquals(105L, PetCurve.totalForLevel(3, PARAMS));
        assertEquals(1260L, PetCurve.totalForLevel(10, PARAMS));
    }

    @Test
    void levelBoundariesAreExact() {
        assertEquals(1, PetCurve.levelFor(39L, PARAMS));
        assertEquals(2, PetCurve.levelFor(40L, PARAMS));
        assertEquals(2, PetCurve.levelFor(104L, PARAMS));
        assertEquals(3, PetCurve.levelFor(105L, PARAMS));
        assertEquals(10, PetCurve.levelFor(1260L, PARAMS));
        assertEquals(10, PetCurve.levelFor(1_000_000L, PARAMS));
    }

    @Test
    void progressReportsIntoAndNeed() {
        PetCurve.LevelProgress progress = PetCurve.progress(50L, PARAMS);
        assertEquals(2, progress.level());
        assertEquals(10L, progress.into());
        assertEquals(65L, progress.need());
        assertEquals(15, progress.percent());
    }

    @Test
    void maxLevelHasNoNextStep() {
        PetCurve.LevelProgress progress = PetCurve.progress(2000L, PARAMS);
        assertEquals(10, progress.level());
        assertEquals(0L, progress.need());
        assertEquals(100, progress.percent());
    }

    @Test
    void missingForLevelCountsForwardOnly() {
        assertEquals(40L, PetCurve.missingForLevel(0L, 2, PARAMS));
        assertEquals(0L, PetCurve.missingForLevel(500L, 3, PARAMS));
    }

    @Test
    void barHasRequestedWidth() {
        assertEquals("░░░░░░░░░░", PetCurve.bar(0, 10));
        assertEquals("██████████", PetCurve.bar(100, 10));
        String half = PetCurve.bar(50, 10);
        assertEquals(10, half.length());
        assertTrue(half.startsWith("█████"));
    }

    @Test
    void paramsClampToSafeRange() {
        PetCurve.Params params = new PetCurve.Params(1, 0L, -10.0);
        assertEquals(2, params.maxLevel());
        assertEquals(1L, params.baseXp());
        assertEquals(0.0, params.growth(), 1.0e-9);
    }
}
