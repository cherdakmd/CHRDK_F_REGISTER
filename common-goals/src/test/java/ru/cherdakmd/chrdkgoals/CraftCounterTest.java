package ru.cherdakmd.chrdkgoals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftCounterTest {

    @Test
    void plainClickCountsOneResult() {
        assertEquals(4, CraftCounter.craftedAmount(false, 4, new int[]{1, 1, 1, 1}));
        assertEquals(1, CraftCounter.craftedAmount(false, 1, new int[]{64}));
    }

    @Test
    void shiftClickScalesByThinnestIngredient() {
        // палки: 2 доски → 4 палки; shift при 32 и 64 досках = 32 крафта × 4
        assertEquals(128, CraftCounter.craftedAmount(true, 4, new int[]{32, 64}));
        // ограничение — самый «тонкий» ингредиент
        assertEquals(6, CraftCounter.craftedAmount(true, 3, new int[]{2, 9, 9}));
    }

    @Test
    void emptyOrInvalidMatrixGivesZero() {
        assertEquals(0, CraftCounter.craftedAmount(true, 1, new int[]{0, 0, 0}));
        assertEquals(0, CraftCounter.craftedAmount(true, 1, new int[0]));
        assertEquals(0, CraftCounter.craftedAmount(false, 0, new int[]{8}));
        assertEquals(0, CraftCounter.craftedAmount(true, -1, new int[]{8}));
    }
}
