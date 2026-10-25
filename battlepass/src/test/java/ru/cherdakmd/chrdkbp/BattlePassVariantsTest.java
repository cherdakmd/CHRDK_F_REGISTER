package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Варианты блоков: руда и её глубокосланцевая форма, бревно и его обтёсанная форма. */
class BattlePassVariantsTest {

    @Test
    void deepslateOreMatchesPlainOre() {
        assertTrue(BattlePassVariants.sameMaterial("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"));
        assertTrue(BattlePassVariants.sameMaterial("deepslate_gold_ore", "gold_ore"));
        assertTrue(BattlePassVariants.sameMaterial("DIAMOND_ORE", "diamond_ore"));
    }

    @Test
    void deepslateBuildingBlocksAreNotOres() {
        assertFalse(BattlePassVariants.sameMaterial("BRICKS", "DEEPSLATE_BRICKS"),
                "кирпичи не должны засчитываться за глубокосланцевые кирпичи");
        assertFalse(BattlePassVariants.sameMaterial("TILES", "DEEPSLATE_TILES"));
        assertFalse(BattlePassVariants.sameMaterial("DEEPSLATE", "STONE"));
    }

    @Test
    void strippedWoodMatchesSameSpeciesOnly() {
        assertTrue(BattlePassVariants.sameMaterial("OAK_LOG", "STRIPPED_OAK_LOG"));
        assertTrue(BattlePassVariants.sameMaterial("OAK_LOG", "OAK_WOOD"));
        assertTrue(BattlePassVariants.sameMaterial("STRIPPED_OAK_WOOD", "OAK_LOG"));
        assertFalse(BattlePassVariants.sameMaterial("OAK_LOG", "BIRCH_LOG"),
                "разные породы дерева — разные задания");
        assertFalse(BattlePassVariants.sameMaterial("OAK_LOG", "OAK_PLANKS"));
    }

    @Test
    void canonicalHandlesEmptyAndSpaces() {
        assertEquals("", BattlePassVariants.canonical(null));
        assertEquals("diamond_ore", BattlePassVariants.canonical("  Deepslate_Diamond_Ore "));
        assertEquals("oak_log", BattlePassVariants.canonical("STRIPPED_OAK_WOOD"));
    }

    @Test
    void matchingNullsIsFalse() {
        assertFalse(BattlePassVariants.sameMaterial(null, "STONE"));
        assertFalse(BattlePassVariants.sameMaterial("STONE", null));
    }
}
