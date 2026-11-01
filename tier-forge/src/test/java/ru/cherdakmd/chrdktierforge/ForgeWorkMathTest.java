package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForgeWorkMathTest {
    @Test
    void xpBonusPreservesOrdinaryRoundingAndCannotWrapIntoOneOrNegativeXp() {
        assertEquals(120, ForgeWorkMath.boostedXp(100, .2));
        assertEquals(1, ForgeWorkMath.boostedXp(1, .2));
        assertEquals(Integer.MAX_VALUE, ForgeWorkMath.boostedXp(Integer.MAX_VALUE, .2));
        assertEquals(0, ForgeWorkMath.boostedXp(0, .2));
        assertEquals(-1, ForgeWorkMath.boostedXp(-1, .2));
        assertEquals(5, ForgeWorkMath.boostedXp(5, Double.NaN));
    }

    @Test
    void geometricRepairHandlesZeroAndCertainChanceWithoutRandom() {
        java.util.function.DoubleSupplier never = () -> { throw new AssertionError("Лишний бросок"); };
        assertEquals(0, ForgeWorkMath.repairs(0, 20, .25, never));
        assertEquals(0, ForgeWorkMath.repairs(50, 0, .25, never));
        assertEquals(0, ForgeWorkMath.repairs(50, 20, 0, never));
        assertEquals(0, ForgeWorkMath.repairs(50, 20, Double.NaN, never));
        assertEquals(20, ForgeWorkMath.repairs(50, 20, 1, never));
        assertEquals(5, ForgeWorkMath.repairs(5, 20, 1, never));
    }

    @Test
    void geometricWaitingConsumesXpAndStopsAtTheWear() {
        assertEquals(0, ForgeWorkMath.repairs(1, 20, .5, () -> .5));
        assertEquals(1, ForgeWorkMath.repairs(2, 20, .5, () -> .5));
        assertEquals(3, ForgeWorkMath.repairs(6, 20, .5, () -> .5));
        assertEquals(1, ForgeWorkMath.repairs(100, 1, .5, () -> 0));
        assertEquals(0, ForgeWorkMath.repairs(2, 20, .1, () -> Math.nextDown(1.0)));
    }

    @Test
    void enormousXpPacketDoesNotCauseXpSizedLoop() {
        AtomicInteger draws = new AtomicInteger();
        assertEquals(2030, ForgeWorkMath.repairs(Integer.MAX_VALUE, 2030, .25, () -> {
            assertTrue(draws.incrementAndGet() <= 2030);
            return .5;
        }));
        assertEquals(2030, draws.get());
    }

    @Test
    void seededGeometricSamplingMatchesBinomialDistributionAndWearCensoring() {
        Random random = new Random(266);
        int[] counts = new int[3];
        int censored = 0;
        int trials = 50_000;
        for (int index = 0; index < trials; index++) {
            counts[ForgeWorkMath.repairs(2, 2, .5, random::nextDouble)]++;
            censored += ForgeWorkMath.repairs(2, 1, .5, random::nextDouble);
        }
        assertEquals(.25, counts[0] / (double) trials, .015);
        assertEquals(.50, counts[1] / (double) trials, .015);
        assertEquals(.25, counts[2] / (double) trials, .015);
        assertEquals(.75, censored / (double) trials, .015);
    }

    @Test
    void allDropMultipliersApplyBeforeSplittingButOreCopyIsNotMultipliedAgain() {
        int cropDeep = ForgeWorkMath.dropTotal(64, false, true, false, true);
        assertEquals(256, cropDeep);
        assertEquals(256, DropAmounts.split(cropDeep, 64).stream().mapToInt(Integer::intValue).sum());
        assertEquals(4, DropAmounts.split(cropDeep, 64).size());
        assertEquals(192, ForgeWorkMath.dropTotal(64, true, false, false, true));
        assertEquals(128, ForgeWorkMath.dropTotal(64, true, false, false, false));
        assertEquals(3, ForgeWorkMath.dropTotal(1, true, false, false, true));
        for (int size : DropAmounts.split(ForgeWorkMath.dropTotal(16, false, false, true, true), 16)) {
            assertEquals(16, size);
        }
    }

    @Test
    void feedingNeverOvershootsSixFoodLevelOrReducesExistingSaturation() {
        assertEquals(6f, ForgeWorkMath.feed(5.9f, 20, 4.8));
        assertEquals(3f, ForgeWorkMath.feed(2.9f, 3, 1.2));
        assertEquals(4.4f, ForgeWorkMath.feed(4f, 20, .4), .00001);
        assertEquals(8f, ForgeWorkMath.feed(8f, 20, 1.2));
        assertEquals(5f, ForgeWorkMath.feed(5f, 3, 1.2));
        assertEquals(0f, ForgeWorkMath.feed(0f, 0, 1.2));
        assertEquals(2f, ForgeWorkMath.feed(2f, 20, Double.NaN));
    }

    @Test
    void absolutePropertyStrengthAndMagnetKeepExistingCeilings() {
        assertEquals(0, ForgeWorkMath.columnSides(0));
        assertEquals(1, ForgeWorkMath.columnSides(1));
        assertEquals(2, ForgeWorkMath.columnSides(2));
        assertEquals(2, ForgeWorkMath.columnSides(3));
        assertEquals(0, ForgeWorkMath.columnSides(Double.NaN));
        assertEquals(4, ForgeWorkMath.magnetRadius(5 * .5));
        assertEquals(5.5, ForgeWorkMath.magnetRadius(6 * 5 * .5));
    }

    @Test
    void potionPulsePreservesStrongerInfiniteAndLongerExternalEffects() {
        assertFalse(ForgeWorkMath.refreshPotion(2, 1, 0, 300));
        assertFalse(ForgeWorkMath.refreshPotion(0, -1, 0, 300));
        assertFalse(ForgeWorkMath.refreshPotion(0, 301, 0, 300));
        assertFalse(ForgeWorkMath.refreshPotion(0, 300, 0, 300));
        assertTrue(ForgeWorkMath.refreshPotion(0, 260, 0, 300));
        assertTrue(ForgeWorkMath.refreshPotion(0, 600, 1, 300));
    }

    @Test
    void descriptionsMatchPerLevelAbsoluteValuesAndKnownLegacyDescriptions() {
        var mender = ForgeUpgrade.byId("mender");
        assertEquals("чинит себя: 3 прочности/2с", ForgeUpgrade.describe(mender, 3));
        assertEquals("чинит себя: 1 прочности/2с", ForgeUpgrade.describeBefore266(mender, 3));
        assertEquals("колонна: +1 блок сверху и +1 снизу", ForgeUpgrade.describe(ForgeUpgrade.byId("hewer"), 2));
        assertEquals("копает колонну боков", ForgeUpgrade.describeBefore266(ForgeUpgrade.byId("hewer"), 2));
        assertTrue(ForgeUpgrade.describe(ForgeUpgrade.byId("feeder"), 3).contains("1.2 насыщения"));
        assertTrue(ForgeUpgrade.describe(ForgeUpgrade.byId("prospector"), 4).contains("24%"));
        assertTrue(ForgeUpgrade.describe(ForgeUpgrade.byId("prospector"), 4).contains("не блока"));
    }
}
