package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Ротация по суткам UTC и потолки бонуса без Bukkit и ввода-вывода. */
class ShopPromoMathTest {

    private static final List<String> POOL = List.of(
            "DIRT", "STONE", "SAND", "OAK_LOG", "COAL", "IRON_INGOT", "WHEAT");

    @Test
    void utcDayIsStableAndMatchesDailyTurnoverWindow() {
        long day = 20_000L;
        long start = day * ShopPromoMath.DAY_MILLIS;
        assertEquals(day, ShopPromoMath.dayIndex(start));
        assertEquals(day, ShopPromoMath.dayIndex(start + ShopPromoMath.DAY_MILLIS - 1));
        assertEquals(day + 1, ShopPromoMath.dayIndex(start + ShopPromoMath.DAY_MILLIS));
        assertEquals(start, ShopPromoMath.dayStart(start + 12345));
        assertEquals(-1, ShopPromoMath.dayIndex(-1), "отрицательное время — предыдущие сутки");
    }

    @Test
    void threeTypesCycleEveryDay() {
        Set<ShopPromoMath.Type> seen = new HashSet<>();
        for (long day = 0; day < 12; day++) {
            seen.add(ShopPromoMath.typeFor(day));
            assertEquals(ShopPromoMath.typeFor(day), ShopPromoMath.typeFor(day + 3));
        }
        assertEquals(3, seen.size());
    }

    @Test
    void featuredAreDeterministicDistinctAndInsidePool() {
        for (long day = 0; day < 30; day++) {
            List<String> today = ShopPromoMath.featured(POOL, 3, day);
            assertEquals(3, today.size());
            assertEquals(3, new HashSet<>(today).size(), "товары дня не повторяются");
            assertTrue(POOL.containsAll(today));
            assertEquals(today, ShopPromoMath.featured(POOL, 3, day), "один день = один набор");
        }
        assertTrue(ShopPromoMath.featured(POOL, 3, 0) != null);
        assertNotEquals(ShopPromoMath.featured(POOL, 3, 0),
                ShopPromoMath.featured(POOL, 3, 1), "соседние дни дают разные наборы");
        assertEquals(List.of(), ShopPromoMath.featured(List.of(), 3, 0));
        assertEquals(List.of("DIRT"), ShopPromoMath.featured(List.of("DIRT"), 3, 0));
    }

    @Test
    void capNeverBreaksAntiArbitrageOrLimits() {
        assertEquals(25, ShopPromoMath.cap(25, 100, 32, 128, 1000));
        assertEquals(50, ShopPromoMath.cap(200, 50, 1000, 1000, 1000));
        assertEquals(32, ShopPromoMath.cap(200, 1000, 32, 1000, 1000));
        assertEquals(128, ShopPromoMath.cap(200, 1000, 1000, 128, 1000));
        assertEquals(7, ShopPromoMath.cap(200, 1000, 1000, 1000, 7),
                "персональный дневной оборот — последний рубеж");
        assertEquals(0, ShopPromoMath.cap(200, 0, 1000, 1000, 1000),
                "выплата не может выйти за цену покупки того же объёма");
    }

    @Test
    void percentLotsAndAskTotalAreSafe() {
        assertEquals(25, ShopPromoMath.percentOf(100, 25));
        assertEquals(0, ShopPromoMath.percentOf(0, 25));
        assertEquals(0, ShopPromoMath.percentOf(100, 0));
        assertEquals(1, ShopPromoMath.lots(16, 1));
        assertEquals(4, ShopPromoMath.lots(16, 64));
        assertEquals(200, ShopPromoMath.askTotal(50, 16, 64));
        assertEquals(0, ShopPromoMath.askTotal(-1, 16, 64));
    }
}
