package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * «Плавильня» (1.4.56): статическая таблица переплавки дропа руд → слитки.
 * Проверяем чистое сопоставление без сервера (это switch по Material).
 */
class ForgeSmeltTest {

    @Test
    void rawAndOreBlocksSmeltIntoIngots() {
        assertEquals(Material.IRON_INGOT, ForgeUpgradeListener.smeltResultOf(Material.RAW_IRON));
        assertEquals(Material.IRON_INGOT, ForgeUpgradeListener.smeltResultOf(Material.IRON_ORE));
        assertEquals(Material.IRON_INGOT,
                ForgeUpgradeListener.smeltResultOf(Material.DEEPSLATE_IRON_ORE));
        assertEquals(Material.GOLD_INGOT, ForgeUpgradeListener.smeltResultOf(Material.RAW_GOLD));
        assertEquals(Material.GOLD_INGOT, ForgeUpgradeListener.smeltResultOf(Material.GOLD_ORE));
        assertEquals(Material.GOLD_INGOT,
                ForgeUpgradeListener.smeltResultOf(Material.DEEPSLATE_GOLD_ORE));
        assertEquals(Material.COPPER_INGOT, ForgeUpgradeListener.smeltResultOf(Material.RAW_COPPER));
        assertEquals(Material.COPPER_INGOT, ForgeUpgradeListener.smeltResultOf(Material.COPPER_ORE));
        assertEquals(Material.COPPER_INGOT,
                ForgeUpgradeListener.smeltResultOf(Material.DEEPSLATE_COPPER_ORE));
        assertEquals(Material.NETHERITE_SCRAP,
                ForgeUpgradeListener.smeltResultOf(Material.ANCIENT_DEBRIS));
    }

    @Test
    void junkDropsStayoutsideSmeltery() {
        assertNull(ForgeUpgradeListener.smeltResultOf(Material.COBBLESTONE));
        assertNull(ForgeUpgradeListener.smeltResultOf(Material.DIAMOND));
        assertNull(ForgeUpgradeListener.smeltResultOf(Material.COAL));
        assertNull(ForgeUpgradeListener.smeltResultOf(null));
    }
}
