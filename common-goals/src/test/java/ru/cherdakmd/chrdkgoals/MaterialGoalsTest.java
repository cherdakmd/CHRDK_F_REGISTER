package ru.cherdakmd.chrdkgoals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialGoalsTest {

    @Test
    void oresCoverDeepVariants() {
        assertTrue(MaterialGoals.isOre(Material.DIAMOND_ORE));
        assertTrue(MaterialGoals.isOre(Material.DEEPSLATE_EMERALD_ORE));
        assertTrue(MaterialGoals.isOre(Material.ANCIENT_DEBRIS));
        assertFalse(MaterialGoals.isOre(Material.COBBLESTONE));
        assertFalse(MaterialGoals.isOre(Material.WHEAT));
    }

    @Test
    void cropsAreGrainsAndVeggies() {
        assertTrue(MaterialGoals.isCrop(Material.WHEAT));
        assertTrue(MaterialGoals.isCrop(Material.PUMPKIN));
        assertFalse(MaterialGoals.isCrop(Material.OAK_LOG));
    }

    @Test
    void diamondEquivalence() {
        assertEquals(1, MaterialGoals.diamondValue(Material.DIAMOND));
        assertEquals(9, MaterialGoals.diamondValue(Material.DIAMOND_BLOCK));
        assertEquals(1, MaterialGoals.diamondValue(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals(0, MaterialGoals.diamondValue(Material.EMERALD));
    }
}
