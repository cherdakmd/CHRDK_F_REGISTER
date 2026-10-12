package ru.cherdakmd.chrdkclaims;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiamondItemsTest {

    @Test
    void valueMappingMatchesShopConventions() {
        assertEquals(1, DiamondItems.valueOf(Material.DIAMOND));
        assertEquals(1, DiamondItems.valueOf(Material.DIAMOND_ORE));
        assertEquals(1, DiamondItems.valueOf(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals(9, DiamondItems.valueOf(Material.DIAMOND_BLOCK));
        assertEquals(0, DiamondItems.valueOf(Material.EMERALD));
        assertEquals(0, DiamondItems.valueOf(Material.NETHERITE_INGOT));
    }
}
