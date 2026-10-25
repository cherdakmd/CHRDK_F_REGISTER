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
        assertEquals(3, ProfessionCatalog.smeltXp(Profession.COOK, Material.COOKED_BEEF));
        assertEquals(2, ProfessionCatalog.smeltXp(Profession.COOK, Material.BAKED_POTATO));
        assertEquals(1, ProfessionCatalog.smeltXp(Profession.MASON, Material.GLASS));
        assertEquals(0, ProfessionCatalog.smeltXp(Profession.ALCHEMIST, Material.IRON_INGOT));
        assertEquals(0, ProfessionCatalog.smeltXp(Profession.SMITH, Material.COOKED_BEEF));
        assertEquals(0, ProfessionCatalog.smeltXp(null, Material.STONE));
    }

    @Test
    void miningIsEverydayWorkForEachProfession() {
        // 1.4.177: камень — работа камнереза, а не «ничего».
        assertEquals(1, ProfessionCatalog.miningXp(Profession.MASON, Material.STONE));
        assertEquals(1, ProfessionCatalog.miningXp(Profession.MASON, Material.DEEPSLATE));
        assertEquals(1, ProfessionCatalog.miningXp(Profession.MASON, Material.COBBLESTONE));
        assertEquals(2, ProfessionCatalog.miningXp(Profession.SMITH, Material.IRON_ORE));
        assertEquals(2, ProfessionCatalog.miningXp(Profession.SMITH, Material.DEEPSLATE_GOLD_ORE));
        assertEquals(4, ProfessionCatalog.miningXp(Profession.SMITH, Material.ANCIENT_DEBRIS));
        assertEquals(1, ProfessionCatalog.miningXp(Profession.COOK, Material.WHEAT));
        assertEquals(2, ProfessionCatalog.miningXp(Profession.ALCHEMIST,
                Material.AMETHYST_CLUSTER));
        // 1.4.178: у алхимика есть работа до Нижнего мира.
        assertEquals(1, ProfessionCatalog.miningXp(Profession.ALCHEMIST, Material.SUGAR_CANE));
        assertEquals(1, ProfessionCatalog.miningXp(Profession.ALCHEMIST, Material.RED_MUSHROOM));
    }

    @Test
    void miningDoesNotLeakBetweenProfessions() {
        assertEquals(0, ProfessionCatalog.miningXp(Profession.MASON, Material.IRON_ORE));
        assertEquals(0, ProfessionCatalog.miningXp(Profession.COOK, Material.STONE));
        assertEquals(0, ProfessionCatalog.miningXp(Profession.SMITH, Material.GLOWSTONE));
        assertEquals(0, ProfessionCatalog.miningXp(Profession.ALCHEMIST, Material.WHEAT));
        assertEquals(0, ProfessionCatalog.miningXp(Profession.MASON, Material.DIAMOND_ORE));
        assertEquals(0, ProfessionCatalog.miningXp(null, Material.STONE));
        assertEquals(0, ProfessionCatalog.miningXp(Profession.MASON, null));
    }

    @Test
    void stonecutterCountsOnlyForMason() {
        assertEquals(1, ProfessionCatalog.cutXp(Profession.MASON, Material.STONE_BRICK_STAIRS));
        assertEquals(1, ProfessionCatalog.cutXp(Profession.MASON, Material.STONE_BRICKS));
        assertEquals(2, ProfessionCatalog.cutXp(Profession.MASON, Material.PRISMARINE_BRICKS));
        assertEquals(0, ProfessionCatalog.cutXp(Profession.SMITH, Material.STONE_BRICKS));
        assertEquals(0, ProfessionCatalog.cutXp(Profession.MASON, Material.IRON_INGOT));
        assertEquals(0, ProfessionCatalog.cutXp(null, Material.STONE_BRICKS));
    }

    @Test
    void everyProfessionHasWorkFromTheFirstDay() {
        // Правило 1.4.177: у ремесла должен быть повседневный источник опыта,
        // иначе выбор выглядит как «ничего не происходит».
        for (Profession profession : Profession.values()) {
            Map<Material, Integer> mining = ProfessionCatalog.miningTable(profession);
            assertFalse(mining.isEmpty(), "добыча " + profession.id());
            assertTrue(mining.values().stream().anyMatch(xp -> xp <= 2),
                    "доступный блок " + profession.id());
        }
        assertFalse(ProfessionCatalog.cutTable(Profession.MASON).isEmpty());
        assertTrue(ProfessionCatalog.cutTable(Profession.SMITH).isEmpty());
        assertTrue(ProfessionCatalog.cutTable(Profession.COOK).isEmpty());
        assertTrue(ProfessionCatalog.cutTable(Profession.ALCHEMIST).isEmpty());
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
    void fullBrewingStandPaysMore() {
        // 1.4.178: три склянки за цикл — 12 опыта, а не 8.
        assertEquals(8, ProfessionCatalog.brewXp(Profession.ALCHEMIST, 1));
        assertEquals(10, ProfessionCatalog.brewXp(Profession.ALCHEMIST, 2));
        assertEquals(12, ProfessionCatalog.brewXp(Profession.ALCHEMIST, 3));
        assertEquals(8, ProfessionCatalog.brewXp(Profession.ALCHEMIST, 0), "склянок нет — платим базовый опыт");
        assertEquals(12, ProfessionCatalog.brewXp(Profession.ALCHEMIST, 9), "потолок — три склянки");
        assertEquals(0, ProfessionCatalog.brewXp(Profession.MASON, 3));
        assertEquals(0, ProfessionCatalog.brewXp(null, 3));
    }

    @Test
    void anvilPaysForTheWorkInLevels() {
        // 1.4.178: наковальня — главный инструмент кузнеца, опыт за неё.
        assertEquals(2, ProfessionCatalog.anvilXp(0), "переименование и мелкая работа — 2");
        assertEquals(2, ProfessionCatalog.anvilXp(-5), "отрицательная стоимость не ломает формулу");
        assertEquals(3, ProfessionCatalog.anvilXp(5));
        assertEquals(4, ProfessionCatalog.anvilXp(10));
        assertEquals(ProfessionCatalog.MAX_ANVIL_XP, ProfessionCatalog.anvilXp(1000));
    }

    @Test
    void smithingTableBelongsToSmith() {
        assertEquals(ProfessionCatalog.SMITHING_XP,
                ProfessionCatalog.smithingXp(Profession.SMITH));
        assertEquals(0, ProfessionCatalog.smithingXp(Profession.MASON));
        assertEquals(0, ProfessionCatalog.smithingXp(Profession.ALCHEMIST));
        assertEquals(0, ProfessionCatalog.smithingXp(Profession.COOK));
        assertEquals(0, ProfessionCatalog.smithingXp(null));
    }

    @Test
    void everyEntryStaysInsideCeiling() {
        for (Profession profession : Profession.values()) {
            Map<Material, Integer> craft = ProfessionCatalog.craftTable(profession);
            Map<Material, Integer> smelt = ProfessionCatalog.smeltTable(profession);
            Map<Material, Integer> mining = ProfessionCatalog.miningTable(profession);
            Map<Material, Integer> cut = ProfessionCatalog.cutTable(profession);
            assertFalse(craft.isEmpty(), "крафт " + profession.id());
            for (int xp : craft.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "крафт " + xp);
            }
            for (int xp : smelt.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "плавка " + xp);
            }
            for (int xp : mining.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "добыча " + xp);
            }
            for (int xp : cut.values()) {
                assertTrue(xp >= 1 && xp <= ProfessionCatalog.MAX_XP, "камнерез " + xp);
            }
        }
    }

    @Test
    void tablesAreReadOnly() {
        Map<Material, Integer> craft = ProfessionCatalog.craftTable(Profession.SMITH);
        assertThrows(UnsupportedOperationException.class, () -> craft.put(Material.DIRT, 99));
        Map<Material, Integer> smelt = ProfessionCatalog.smeltTable(Profession.SMITH);
        assertThrows(UnsupportedOperationException.class, () -> smelt.put(Material.DIRT, 99));
        Map<Material, Integer> mining = ProfessionCatalog.miningTable(Profession.MASON);
        assertThrows(UnsupportedOperationException.class, () -> mining.put(Material.DIRT, 99));
        Map<Material, Integer> cut = ProfessionCatalog.cutTable(Profession.MASON);
        assertThrows(UnsupportedOperationException.class, () -> cut.put(Material.DIRT, 99));
        assertTrue(ProfessionCatalog.craftTable(null).isEmpty());
        assertTrue(ProfessionCatalog.smeltTable(null).isEmpty());
        assertTrue(ProfessionCatalog.miningTable(null).isEmpty());
        assertTrue(ProfessionCatalog.cutTable(null).isEmpty());
    }
}
