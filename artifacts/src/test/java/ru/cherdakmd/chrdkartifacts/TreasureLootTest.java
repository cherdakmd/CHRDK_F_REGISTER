package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureLootTest {

    @Test
    void recognizesAllSixVanillaTreasures() {
        assertEquals(6, TreasureLoot.vanillaTreasure().size(),
                "в категории treasure ровно шесть предметов");
        for (Material treasure : Set.of(Material.BOW, Material.ENCHANTED_BOOK,
                Material.FISHING_ROD, Material.NAME_TAG, Material.NAUTILUS_SHELL,
                Material.SADDLE)) {
            assertTrue(TreasureLoot.isTreasure(treasure), treasure + " должно быть сокровищем");
        }
    }

    @Test
    void junkIsNotTreasure() {
        for (Material junk : Set.of(Material.STICK, Material.BONE, Material.BOWL,
                Material.ROTTEN_FLESH, Material.INK_SAC, Material.LILY_PAD,
                Material.LEATHER_BOOTS, Material.TRIPWIRE_HOOK, Material.STRING,
                Material.POTION, Material.GLASS_BOTTLE)) {
            assertFalse(TreasureLoot.isTreasure(junk), junk + " — мусор, а не сокровище");
        }
    }

    @Test
    void fishAndOrdinaryItemsAreNotTreasure() {
        assertFalse(TreasureLoot.isTreasure(Material.COD));
        assertFalse(TreasureLoot.isTreasure(Material.SALMON));
        assertFalse(TreasureLoot.isTreasure(Material.DIAMOND));
        assertFalse(TreasureLoot.isTreasure(null));
    }
}
