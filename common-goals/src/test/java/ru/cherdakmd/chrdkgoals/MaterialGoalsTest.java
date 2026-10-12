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
    void logsCoverWoodFamilies() {
        assertTrue(MaterialGoals.isLog(Material.OAK_LOG));
        assertTrue(MaterialGoals.isLog(Material.STRIPPED_DARK_OAK_LOG));
        assertTrue(MaterialGoals.isLog(Material.CHERRY_WOOD));
        assertTrue(MaterialGoals.isLog(Material.PALE_OAK_LOG));
        assertTrue(MaterialGoals.isLog(Material.WARPED_STEM));
        assertTrue(MaterialGoals.isLog(Material.STRIPPED_CRIMSON_HYPHAE));
        assertFalse(MaterialGoals.isLog(Material.OAK_PLANKS));
        assertFalse(MaterialGoals.isLog(Material.STICK));
    }

    @Test
    void diggableCoversSoilSandGravel() {
        assertTrue(MaterialGoals.isDiggable(Material.GRASS_BLOCK));
        assertTrue(MaterialGoals.isDiggable(Material.DIRT));
        assertTrue(MaterialGoals.isDiggable(Material.SAND));
        assertTrue(MaterialGoals.isDiggable(Material.RED_SAND));
        assertTrue(MaterialGoals.isDiggable(Material.GRAVEL));
        assertTrue(MaterialGoals.isDiggable(Material.SOUL_SOIL));
        assertTrue(MaterialGoals.isDiggable(Material.CLAY));
        assertTrue(MaterialGoals.isDiggable(Material.SNOW_BLOCK));
        assertFalse(MaterialGoals.isDiggable(Material.STONE));
        assertFalse(MaterialGoals.isDiggable(Material.OAK_LOG));
        assertFalse(MaterialGoals.isDiggable(Material.DIAMOND_ORE));
    }

    @Test
    void diamondEquivalence() {
        assertEquals(1, MaterialGoals.diamondValue(Material.DIAMOND));
        assertEquals(9, MaterialGoals.diamondValue(Material.DIAMOND_BLOCK));
        assertEquals(1, MaterialGoals.diamondValue(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals(0, MaterialGoals.diamondValue(Material.EMERALD));
    }
}
