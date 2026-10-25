package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховочный набор клада: бочка не должна появляться пустой даже при
 * выключенных склянках и пустой лут-таблице.
 */
class TreasureFallbackTest {

    @Test
    void fallbackIsNeverEmptyAndStaysInRange() {
        for (int seed = 0; seed < 200; seed++) {
            List<TreasureLoot.LootStack> loot = TreasureFallback.roll(new Random(seed));
            assertEquals(1, loot.size(), "набор — один стек склянок");
            TreasureLoot.LootStack stack = loot.get(0);
            assertEquals(TreasureExperience.MATERIAL, stack.material());
            assertTrue(stack.amount() >= TreasureFallback.MIN_BOTTLES,
                    "меньше минимума: " + stack.amount());
            assertTrue(stack.amount() <= TreasureFallback.MAX_BOTTLES,
                    "больше максимума: " + stack.amount());
            assertTrue(stack.name().isBlank(), "склянки не именные");
            assertFalse(stack.hasElixir(), "склянки не эликсир");
            assertTrue(TreasureFallback.isFallback(stack));
        }
    }

    @Test
    void nullRandomGivesTheMinimum() {
        List<TreasureLoot.LootStack> loot = TreasureFallback.roll(null);
        assertEquals(1, loot.size());
        assertEquals(TreasureFallback.MIN_BOTTLES, loot.get(0).amount());
    }

    @Test
    void otherLootIsNotMistakenForFallback() {
        assertFalse(TreasureFallback.isFallback(null));
        assertFalse(TreasureFallback.isFallback(
                new TreasureLoot.LootStack("COAL", 3)), "уголь — обычная добыча");
        assertFalse(TreasureFallback.isFallback(
                new TreasureLoot.LootStack(TreasureExperience.MATERIAL, 64)),
                "слишком большая стопка — это уже не страховка");
        assertFalse(TreasureFallback.isFallback(
                new TreasureLoot.LootStack(TreasureExperience.MATERIAL, 3, "Особая склянка")),
                "именные склянки не считаются страховочным набором");
    }
}
