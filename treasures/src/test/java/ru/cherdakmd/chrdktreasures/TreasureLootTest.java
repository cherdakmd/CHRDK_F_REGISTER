package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureLootTest {

    @Test
    void everyTierHasItsOwnNumberOfRolls() {
        assertEquals(1, TreasureLoot.rolls(TreasureTier.COMMON));
        assertEquals(2, TreasureLoot.rolls(TreasureTier.RARE));
        assertEquals(2, TreasureLoot.rolls(TreasureTier.EPIC));
        assertEquals(3, TreasureLoot.rolls(TreasureTier.LEGENDARY));
        assertEquals(4, TreasureLoot.rolls(TreasureTier.MYTHICAL));
    }

    @Test
    void allPoolsContainRealItems() {
        Set<String> materials = new HashSet<>();
        for (TreasureTier tier : TreasureTier.values()) {
            List<TreasureLoot.LootEntry> pool = TreasureLoot.poolOf(tier);
            assertFalse(pool.isEmpty(), "пустой пул у " + tier.id());
            for (TreasureLoot.LootEntry entry : pool) {
                materials.add(entry.material());
                assertValidItem(entry);
            }
        }
        for (TreasureRegion region : TreasureRegion.values()) {
            List<TreasureLoot.LootEntry> flavor = TreasureLoot.flavorOf(region);
            assertFalse(flavor.isEmpty(), "нет биомной добычи у " + region.id());
            for (TreasureLoot.LootEntry entry : flavor) {
                materials.add(entry.material());
                assertValidItem(entry);
            }
        }
        assertTrue(materials.size() >= 30, "слишком мало разных материалов: " + materials.size());
    }

    private static void assertValidItem(TreasureLoot.LootEntry entry) {
        Material material;
        try {
            material = Material.valueOf(entry.material());
        } catch (IllegalArgumentException exception) {
            throw new AssertionError("неизвестный материал " + entry.material(), exception);
        }
        assertFalse(material.isAir(), "воздух в добыче: " + entry.material());
        assertTrue(entry.min() >= 1 && entry.max() >= entry.min());
        assertTrue(entry.max() <= 64, "стек больше 64: " + entry.material());
        assertTrue(entry.weight() >= 1);
    }

    @Test
    void rollAggregatesStacksAndCapsAtSixtyFour() {
        Random random = new Random(42L);
        for (TreasureTier tier : TreasureTier.values()) {
            for (TreasureRegion region : TreasureRegion.values()) {
                List<TreasureLoot.LootStack> loot = TreasureLoot.roll(tier, region, random);
                assertFalse(loot.isEmpty(), "пустой клад " + tier.id() + "/" + region.id());
                Set<String> materials = new HashSet<>();
                for (TreasureLoot.LootStack stack : loot) {
                    assertTrue(materials.add(stack.material()),
                            "материал не сложился: " + stack.material());
                    assertTrue(stack.amount() >= 1 && stack.amount() <= 64,
                            "кривое количество " + stack.amount());
                    assertFalse(Material.valueOf(stack.material()).isAir());
                }
            }
        }
    }

    @Test
    void nullRegionStillGivesTierLoot() {
        List<TreasureLoot.LootStack> loot = TreasureLoot.roll(
                TreasureTier.MYTHICAL, null, new Random(7L));
        assertFalse(loot.isEmpty());
    }
}
