package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AnvilPriceTest {
    @Test void mergeUsesActualResultLevel() {
        assertEquals(3, AnvilPrice.merge(2, 2, 5));
        assertEquals(5, AnvilPrice.merge(5, 5, 5));
        assertEquals(4, AnvilPrice.merge(4, 1, 5));
        assertEquals(4, AnvilPrice.merge(1, 4, 5));
        assertEquals(1, AnvilPrice.merge(0, 1, 5));
    }
    @Test void chargeEachImprovedEnchantAtItsOwnRarityAndMergedLevel() {
        assertEquals(9, AnvilPrice.surcharge(Map.of("a", 2), Map.of("a", 3), Map.of("a", 2)));
        // Не max rarity * max level: редкая I и обычная V имеют разные цены; обе учитываются.
        assertEquals(23, AnvilPrice.surcharge(Map.of(), Map.of("a", 1, "b", 5), Map.of("a", 10, "b", 2)));
    }
    @Test void unchangedOrUnrelatedEnchantDoesNotGenerateSurcharge() {
        assertEquals(0, AnvilPrice.surcharge(Map.of("a", 5), Map.of("a", 5, "b", 3), Map.of("a", 2)));
        assertEquals(0, AnvilPrice.surcharge(Map.of(), Map.of(), Map.of()));
    }
    @Test void pureBookKeepsBothPriorWorkPenaltiesAndRename() {
        assertEquals(19, AnvilPrice.base(-1, 15, 3, 1, true));
        assertEquals(18, AnvilPrice.base(0, 15, 3, 0, true));
        assertEquals(54, AnvilPrice.total(AnvilPrice.base(-1, 31, 15, 1, true), 7));
    }
    @Test void mixedBookAddsToNativeNotDuplicatePriorWork() {
        assertEquals(29, AnvilPrice.base(29, 7, 3, 1, false));
        assertEquals(38, AnvilPrice.total(29, 9));
    }
    @Test void renameOnlyCapCannotDiscountCustomTransfer() {
        assertEquals(104, AnvilPrice.total(AnvilPrice.base(39, 100, 0, 1, true), 3));
        assertEquals(50, AnvilPrice.base(50, 1, 0, 1, true)); // Не снижаем чужую цену.
    }
    @Test void nativeLimitAndCreativeExceptionAreRespected() {
        assertTrue(AnvilPrice.allowed(39, 40, false));
        assertFalse(AnvilPrice.allowed(40, 40, false));
        assertFalse(AnvilPrice.allowed(53, 40, false));
        assertTrue(AnvilPrice.allowed(53, 40, true));
        assertTrue(AnvilPrice.allowed(53, 60, false));
        assertFalse(AnvilPrice.allowed(-1, 40, true));
        assertFalse(AnvilPrice.allowed(0, 0, false));
    }
    @Test void cannotResurrectRejectedMixedNativeRecipe() {
        assertFalse(AnvilPrice.nativeCompatible(false, false, -1));
        assertFalse(AnvilPrice.nativeCompatible(false, false, 40));
        assertFalse(AnvilPrice.nativeCompatible(false, true, -1));
        assertTrue(AnvilPrice.nativeCompatible(false, true, 18));
        assertTrue(AnvilPrice.nativeCompatible(true, false, -1));
    }
    @Test void priorWorkGrowsOnceAndHonorsPurpurCumulativeSwitch() {
        assertEquals(1, AnvilPrice.nextWork(0, 0, true));
        assertEquals(15, AnvilPrice.nextWork(3, 7, true));
        assertEquals(0, AnvilPrice.nextWork(31, 15, false));
        assertEquals(Integer.MAX_VALUE, AnvilPrice.nextWork(Integer.MAX_VALUE, 0, true));
    }
    @Test void arithmeticSaturatesWithoutOverflowOrMillionLevelDiscount() {
        assertEquals(2_000_007, AnvilPrice.total(2_000_000, 7));
        assertEquals(Integer.MAX_VALUE, AnvilPrice.total((long) Integer.MAX_VALUE * 2, 53));
        assertEquals(Integer.MAX_VALUE, AnvilPrice.total(Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> AnvilPrice.base(1, -1, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> AnvilPrice.nextWork(1, -1, true));
        assertThrows(IllegalArgumentException.class, () -> AnvilPrice.total(-1, 3));
    }
}
