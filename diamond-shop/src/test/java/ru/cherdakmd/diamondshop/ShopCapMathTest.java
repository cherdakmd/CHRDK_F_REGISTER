package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Персональный дневной лимит оборота: аддитивность, клампы и потолок. */
final class ShopCapMathTest {

    private static final int BASE = ShopCapMath.Settings.DEFAULT.base();

    @Test
    void newcomerGetsExactlyTheBase() {
        assertEquals(BASE, ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                ShopCapMath.Context.NONE));
    }

    @Test
    void fullContextAddsEverySourceWithoutMultipliers() {
        // ранг 5·256 + 50·4 + 25·8 + 10·16 = 1840; престиж 1600; уровни 800;
        // Pass 256 + буст 256 + донат 128. Итого 2048 + 4880 = 6928.
        int expected = BASE + 1840 + 1600 + 800 + 256 + 256 + 128;
        assertEquals(6928, expected);
        assertEquals(expected, ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                ShopCapMath.Context.full()));

        var b = ShopCapMath.breakdown(BASE, ShopCapMath.Settings.DEFAULT,
                ShopCapMath.Context.full());
        assertEquals(BASE, b.base());
        assertEquals(1840, b.rank());
        assertEquals(1600, b.prestige());
        assertEquals(800, b.levels());
        assertEquals(256, b.pass());
        assertEquals(256, b.boost());
        assertEquals(128, b.entitlement());
        assertEquals(expected, b.total());
    }

    @Test
    void sourcesAreIndependent() {
        int rankOnly = ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                new ShopCapMath.Context(5, 0, 0, 0, 0, 0, false, false, false));
        int prestigeOnly = ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                new ShopCapMath.Context(0, 0, 0, 0, 100, 0, false, false, false));
        int passOnly = ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                new ShopCapMath.Context(0, 0, 0, 0, 0, 0, true, false, false));
        assertEquals(BASE + 5 * 256, rankOnly);
        assertEquals(BASE + 100 * 16, prestigeOnly);
        assertEquals(BASE + 256, passOnly);
    }

    @Test
    void contextAndSettingsAreClamped() {
        ShopCapMath.Context wild = new ShopCapMath.Context(
                99, 999, 999, 999, 999, 99_999, true, true, true);
        assertEquals(ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT,
                ShopCapMath.Context.full()),
                ShopCapMath.compute(BASE, ShopCapMath.Settings.DEFAULT, wild));

        ShopCapMath.Settings crazy = new ShopCapMath.Settings(true, -5, -5, -5, -5, -5,
                -5, -5, -5, -5, -5, "  SHOP-LIMIT-BOOST  ", 99_999);
        assertEquals(0, crazy.perRankLevel());
        assertEquals("shop-limit-boost", crazy.entitlementId());
        assertEquals(ShopCapMath.HARD_MAX, crazy.max());
        // base передаётся отдельно; с нулевой базой и обнулёнными коэффициентами
        // лимит зажимается в 1, а не превращается в 0 или отрицательное число.
        assertEquals(1, ShopCapMath.compute(0, crazy, ShopCapMath.Context.NONE));
    }

    @Test
    void configMaxAndDisabledAreHonoured() {
        ShopCapMath.Settings small = new ShopCapMath.Settings(true, 2048, 256, 4, 8, 16,
                16, 8, 256, 256, 128, "shop-limit-boost", 100);
        assertEquals(100, ShopCapMath.compute(2048, small, ShopCapMath.Context.full()));
        ShopCapMath.Settings off = new ShopCapMath.Settings(false, 2048, 256, 4, 8, 16,
                16, 8, 256, 256, 128, "shop-limit-boost", 16_384);
        assertEquals(0, ShopCapMath.compute(2048, off, ShopCapMath.Context.full()));
        assertTrue(ShopCapMath.breakdown(2048, off, ShopCapMath.Context.full()).total() == 0);
    }

    @Test
    void nullInputsDegradeToDefaultsAndBase() {
        assertEquals(BASE, ShopCapMath.compute(BASE, null, null));
        assertEquals(1, ShopCapMath.compute(-10, ShopCapMath.Settings.DEFAULT,
                ShopCapMath.Context.NONE));
    }
}
