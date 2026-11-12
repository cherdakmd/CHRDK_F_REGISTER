package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Пять ресурсов на шесть часов и суточные потолки без Bukkit и ввода-вывода. */
class ShopPromoMathTest {

    private static final List<String> POOL = List.of(
            "DIRT", "STONE", "SAND", "OAK_LOG", "COAL", "IRON_INGOT", "WHEAT",
            "GRAVEL", "REDSTONE", "GOLD_INGOT", "SPRUCE_LOG", "RAW_IRON", "COBBLESTONE");

    @Test
    void utcDayAndSixHourWindowsAreIndependent() {
        long start = 20_000L * ShopPromoMath.DAY_MILLIS;
        assertEquals(20_000L, ShopPromoMath.dayIndex(start));
        assertEquals(20_000L, ShopPromoMath.dayIndex(start + ShopPromoMath.DAY_MILLIS - 1));
        assertEquals(start, ShopPromoMath.dayStart(start + 12345));
        assertEquals(-1, ShopPromoMath.dayIndex(-1));
        for (int window = 0; window < 4; window++) {
            long now = start + window * ShopPromoMath.WINDOW_MILLIS;
            assertEquals(ShopPromoMath.windowIndex(start) + window, ShopPromoMath.windowIndex(now));
            assertEquals(ShopPromoMath.windowIndex(now),
                    ShopPromoMath.windowIndex(now + ShopPromoMath.WINDOW_MILLIS - 1));
        }
        assertEquals(ShopPromoMath.windowIndex(start) + 4,
                ShopPromoMath.windowIndex(start + ShopPromoMath.DAY_MILLIS));
    }

    @Test
    void hotBuybackRunsEveryDayAndChangesFiveResourcesEveryWindow() {
        for (long window = 0; window < 20; window++) {
            assertEquals(ShopPromoMath.Type.HOT_BUYBACK, ShopPromoMath.typeFor(window));
            List<String> current = ShopPromoMath.featured(POOL, 5, window);
            List<String> next = ShopPromoMath.featured(POOL, 5, window + 1);
            assertEquals(5, current.size());
            assertEquals(5, new HashSet<>(current).size());
            assertTrue(POOL.containsAll(current));
            assertEquals(current, ShopPromoMath.featured(POOL, 5, window));
            Set<String> intersection = new HashSet<>(current);
            intersection.retainAll(next);
            assertTrue(intersection.isEmpty(), "соседние окна должны давать пять новых ресурсов");
        }
        assertEquals(List.of(), ShopPromoMath.featured(List.of(), 5, 0));
        assertEquals(List.of("DIRT"), ShopPromoMath.featured(List.of("DIRT"), 5, 0));
    }

    @Test
    void capNeverBreaksAntiArbitrageOrLimits() {
        assertEquals(25, ShopPromoMath.cap(25, 100, 32, 128, 1000));
        assertEquals(50, ShopPromoMath.cap(200, 50, 1000, 1000, 1000));
        assertEquals(5000, ShopPromoMath.cap(6000, 10000, 5000, 10000, 10000));
        assertEquals(128, ShopPromoMath.cap(200, 1000, 1000, 128, 1000));
        assertEquals(7, ShopPromoMath.cap(200, 1000, 1000, 1000, 7));
        assertEquals(0, ShopPromoMath.cap(200, 0, 1000, 1000, 1000));
    }

    @Test
    void percentLotsAndAskTotalAreSafe() {
        assertEquals(50, ShopPromoMath.percentOf(100, 50));
        assertEquals(0, ShopPromoMath.percentOf(0, 50));
        assertEquals(0, ShopPromoMath.percentOf(100, 0));
        assertEquals(1, ShopPromoMath.lots(16, 1));
        assertEquals(4, ShopPromoMath.lots(16, 64));
        assertEquals(200, ShopPromoMath.askTotal(50, 16, 64));
        assertEquals(0, ShopPromoMath.askTotal(-1, 16, 64));
    }
}
