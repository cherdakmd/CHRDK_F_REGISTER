package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Таблицы опыта профессий (1.4.165).
 *
 * <p>Опыт даётся за конкретные предметы, а не за «любой крафт»: иначе его
 * фармили бы кнопками и ковриками. Таблица проверяется на границы — чужой
 * предмет и {@code null} не должны давать опыт ни одной профессии.</p>
 */
class ProfessionCatalogTest {

    @Test
    void craftTableKnowsEachProfession() {
        assertEquals(4, ProfessionCatalog.craftXp(Profession.SMITH, Material.IRON_SWORD));
        assertEquals(14, ProfessionCatalog.craftXp(Profession.SMITH,
                Material.NETHERITE_CHESTPLATE));
        assertEquals(1, ProfessionCatalog.craftXp(Profession.SMITH, Material.RAIL));
        assertEquals(8, ProfessionCatalog.craftXp(Profession.ALCHEMIST,
                Material.BREWING_STAND));
        assertEquals(20, ProfessionCatalog.craftXp(Profession.COOK,
                Material.ENCHANTED_GOLDEN_APPLE));
        assertEquals(2, ProfessionCatalog.craftXp(Profession.MASON, Material.QUARTZ_BLOCK));
        assertEquals(1, ProfessionCatalog.craftXp(Profession.MASON, Material.GLASS_PANE));
    }

    @Test
    void craftIsLimitedToOwnProfession() {
        assertEquals(0, ProfessionCatalog.craftXp(Profession.SMITH, Material.ENCHANTED_GOLDEN_APPLE));
        assertEquals(0, ProfessionCatalog.craftXp(Profession.COOK, Material.DIAMOND_SWORD));
        assertEquals(0, ProfessionCatalog.craftXp(Profession.MASON, Material.BREWING_STAND));
        assertEquals(0, ProfessionCatalog.craftXp(Profession.ALCHEMIST, Material.CAKE));
        assertEquals(0, ProfessionCatalog.craftXp(Profession.SMITH, Material.DIRT));
        assertEquals(0, ProfessionCatalog.craftXp(null, Material.IRON_SWORD));
        assertEquals(0, ProfessionCatalog.craftXp(Profession.SMITH, null));
    }

    @Test
    void smeltTableCoversMetalsFoodAndStone() {
        assertEquals(3, ProfessionCatalog.smeltXp(Profession.SMITH, Material.IRON_INGOT));
        assertEquals(3, ProfessionCatalog.smeltXp(Profession.SMITH, Material.GOLD_INGOT));
        assertEquals(4, ProfessionCatalog.smeltXp(Profession.SMITH, Material.NETHERITE_SCRAP));
        assertEquals(1, ProfessionCatalog.smeltXp(Profession.SMITH, Material.COPPER_INGOT));
        assertEquals(2, ProfessionCatalog.smeltXp(Profession.COOK, Material.COOKED_BEEF));
        assertEquals(1, ProfessionCatalog.smeltXp(Profession.COOK, Material.BAKED_POTATO));
        assertEquals(1, ProfessionCatalog.smeltXp(Profession.MASON, Material.GLASS));
        assertEquals(0, ProfessionCatalog.smeltXp(Profession.ALCHEMIST, Material.IRON_INGOT));
        assertEquals(0, ProfessionCatalog.smeltXp(Profession.SMITH, Material.COOKED_BEEF));
        assertEquals(0, ProfessionCatalog.smeltXp(null, Material.STONE));
    }

    @Test
    void brewingBelongsToAlchemistOnly() {
        assertEquals(8, ProfessionCatalog.brewXp(Profession.ALCHEMIST));
        assertEquals(0, ProfessionCatalog.brewXp(Profession.SMITH));
        assertEquals(0, ProfessionCatalog.brewXp(Profession.COOK));
        assertEquals(0, ProfessionCatalog.brewXp(Profession.MASON));
        assertEquals(0, ProfessionCatalog.brewXp(null));
    }

    @Test
    void everyEntryStaysInsideCeiling() {
        for (Profession profession : Profession.values()) {
            Map<Material, Integer> craft = ProfessionCatalog.craftTable(profession);
            Map<Material, Integer> smelt = ProfessionCatalog.smeltTable(profession);
            assertFalse(craft.isEmpty(), "крафт " + profession.id());
            for (int xp : craft.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "крафт " + xp);
            }
            for (int xp : smelt.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "плавка " + xp);
            }
        }
    }

    @Test
    void tablesAreReadOnly() {
        Map<Material, Integer> craft = ProfessionCatalog.craftTable(Profession.SMITH);
        assertThrows(UnsupportedOperationException.class, () -> craft.put(Material.DIRT, 99));
        Map<Material, Integer> smelt = ProfessionCatalog.smeltTable(Profession.SMITH);
        assertThrows(UnsupportedOperationException.class, () -> smelt.put(Material.DIRT, 99));
        assertTrue(ProfessionCatalog.craftTable(null).isEmpty());
        assertTrue(ProfessionCatalog.smeltTable(null).isEmpty());
    }
}
