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

    @Test
    void cropAndLogClassifiersCoverTheirVocabulary() {
        // Земледел: культуры — да, строительные блоки — нет
        assertBool(ForgeUpgradeListener.isCropBlock(Material.WHEAT));
        assertBool(ForgeUpgradeListener.isCropBlock(Material.CARROTS));
        assertBool(ForgeUpgradeListener.isCropBlock(Material.NETHER_WART));
        deny(ForgeUpgradeListener.isCropBlock(Material.STONE));
        deny(ForgeUpgradeListener.isCropBlock(null));
        // Лесник: брёвна/древесина/стебли/гифы — да, доски — нет
        assertBool(ForgeUpgradeListener.isLogBlock(Material.OAK_LOG));
        assertBool(ForgeUpgradeListener.isLogBlock(Material.CRIMSON_STEM));
        assertBool(ForgeUpgradeListener.isLogBlock(Material.WARPED_HYPHAE));
        deny(ForgeUpgradeListener.isLogBlock(Material.OAK_PLANKS));
        deny(ForgeUpgradeListener.isLogBlock(null));
        // Златолюб: камень без кварца — да, кирковые породы из другого списка — нет
        assertBool(ForgeUpgradeListener.isStoneForGold(Material.DEEPSLATE));
        assertBool(ForgeUpgradeListener.isStoneForGold(Material.TUFF));
        deny(ForgeUpgradeListener.isStoneForGold(Material.GOLD_ORE));
        deny(ForgeUpgradeListener.isStoneForGold(null));
    }

    private static void assertBool(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value);
    }

    private static void deny(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}
