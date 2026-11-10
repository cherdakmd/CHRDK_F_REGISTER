package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Баланс персонального дневного лимита: аддитивность, клампы и потолок. */
final class DiamondCapMathTest {

    private static final DiamondCapMath.Settings S = DiamondCapMath.Settings.DEFAULT;

    private static DiamondCapMath.Context full() {
        return new DiamondCapMath.Context(5, 50, 25, 10, 100, 1000,
                true, true, true);
    }

    @Test
    void playerWithoutProgressionGetsOnlyTheBase() {
        DiamondCapMath.Breakdown breakdown =
                DiamondCapMath.breakdown(256, S, DiamondCapMath.Context.NONE);
        assertEquals(256, breakdown.total());
        assertEquals(0, breakdown.rank());
        assertEquals(0, breakdown.prestige());
        assertEquals(0, breakdown.levels());
        assertEquals(0, breakdown.pass());
        assertEquals(0, breakdown.boost());
        assertEquals(0, breakdown.entitlement());
        assertEquals(256, DiamondCapMath.compute(256, S, DiamondCapMath.Context.NONE));
    }

    @Test
    void everySourceAddsItsConfiguredAmount() {
        DiamondCapMath.Breakdown breakdown = DiamondCapMath.breakdown(256, S, full());
        assertEquals(300, breakdown.rank(), "160 + 50 + 50 + 40");
        assertEquals(400, breakdown.prestige(), "100 ступеней × 4");
        assertEquals(200, breakdown.levels(), "1000 уровней / 10 × 2");
        assertEquals(64, breakdown.pass());
        assertEquals(64, breakdown.boost());
        assertEquals(32, breakdown.entitlement());
        assertEquals(1316, breakdown.total());
        assertEquals(1316, DiamondCapMath.compute(256, S, full()));
    }

    @Test
    void eachSourceContributesIndependently() {
        int base = DiamondCapMath.compute(256, S, DiamondCapMath.Context.NONE);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(1, 0, 0, 0, 0, 0, false, false, false)) > base);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 1, 0, false, false, false)) > base);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 10, false, false, false)) > base);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 0, true, false, false)) > base);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 0, false, true, false)) > base);
        assertTrue(DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 0, false, false, true)) > base);
    }

    @Test
    void onlyFullTenLevelTiersCount() {
        int zero = DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 9, false, false, false));
        int ten = DiamondCapMath.compute(256, S,
                new DiamondCapMath.Context(0, 0, 0, 0, 0, 10, false, false, false));
        assertEquals(256, zero);
        assertEquals(258, ten);
    }

    @Test
    void wildContextAndConfigCannotBreakTheContractCeiling() {
        DiamondCapMath.Settings wild = new DiamondCapMath.Settings(
                99999, 99999, 99999, 99999, 99999, 99999,
                99999, 99999, 99999, "  DIAMOND-CAP-BOOST  ", 99999);
        assertEquals("diamond-cap-boost", wild.entitlementId());
        assertEquals(DiamondDrops.MAX_DAILY_CAP, wild.max());
        assertEquals(4096, DiamondDrops.MAX_DAILY_CAP);
        assertEquals(4096, DiamondCapMath.compute(99999, wild, full()));

        DiamondCapMath.Settings smallMax = new DiamondCapMath.Settings(
                32, 1, 2, 4, 4, 2, 64, 64, 32, "diamond-cap-boost", 300);
        assertEquals(300, DiamondCapMath.compute(256, smallMax, full()));
    }

    @Test
    void clampsProtectAgainstBadNumbers() {
        DiamondCapMath.Settings bad = new DiamondCapMath.Settings(
                -5, -5, -5, -5, -5, -5, -5, -5, -5, null, -10);
        assertEquals(0, bad.perRankLevel());
        assertEquals(1, bad.max());
        assertEquals("", bad.entitlementId());
        assertEquals(1, DiamondCapMath.compute(256, bad, full()),
                "мягкий потолок конфига = 1 важнее базы");

        DiamondCapMath.Context wild = new DiamondCapMath.Context(
                99, 999, 999, 999, 999, 9999, false, false, false);
        assertEquals(5, wild.rankLevel());
        assertEquals(50, wild.rankXpPercent());
        assertEquals(25, wild.rankDropPercent());
        assertEquals(10, wild.rankExtraSkill());
        assertEquals(100, wild.totalPrestige());
        assertEquals(1000, wild.totalLevel());
    }

    @Test
    void nullInputsFallBackToDefaults() {
        assertEquals(256, DiamondCapMath.compute(256, null, null));
        assertTrue(DiamondCapMath.compute(-10, S, DiamondCapMath.Context.NONE) >= 1,
                "база не может стать нулём или отрицательной");
    }
}
