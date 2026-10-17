package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureLootTest {

    @Test
    void recognizesVanillaTreasureButNotFish() {
        assertTrue(TreasureLoot.isTreasure(Material.ENCHANTED_BOOK));
        assertTrue(TreasureLoot.isTreasure(Material.NAUTILUS_SHELL));
        assertTrue(TreasureLoot.isTreasure(Material.SADDLE));
        assertFalse(TreasureLoot.isTreasure(Material.COD));
        assertFalse(TreasureLoot.isTreasure(Material.SALMON));
        assertFalse(TreasureLoot.isTreasure(Material.DIAMOND));
    }
}
