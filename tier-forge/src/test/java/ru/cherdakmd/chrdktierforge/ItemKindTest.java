package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Роли предмета по имени материала (1.4.142): утилита — четвёртая роль после
 * оружия, брони и инструментов. Раньше щит, рог и фонарь давали {@code null},
 * и тир им не доставался вовсе.
 */
class ItemKindTest {

    @Test
    void utilityMaterialsFromTheArsenalAreRecognised() {
        for (String material : List.of("SHIELD", "GOAT_HORN", "ECHO_SHARD",
                "HEART_OF_THE_SEA", "SOUL_LANTERN", "CLOCK", "SUNFLOWER",
                "POINTED_DRIPSTONE", "WITHER_SKELETON_SKULL")) {
            assertEquals(ItemKind.UTILITY, ItemKind.classify(material),
                    material + " — утилита арсенала");
        }
        assertEquals(ItemKind.UTILITY, ItemKind.classify("TOTEM_OF_UNDYING"));
        assertEquals(ItemKind.UTILITY, ItemKind.classify("shield"), "регистр не важен");
    }

    @Test
    void utilityIsNeitherWeaponNorToolNorArmor() {
        assertTrue(ItemKind.UTILITY.isUtility());
        assertFalse(ItemKind.UTILITY.isWeapon(), "щит не оружие");
        assertFalse(ItemKind.UTILITY.isArmor());
        assertFalse(ItemKind.UTILITY.isTool(), "рогом не копают");
        assertFalse(ItemKind.UTILITY.isMelee());
        assertFalse(ItemKind.UTILITY.isRanged());
        for (ItemKind kind : List.of(ItemKind.SWORD, ItemKind.AXE, ItemKind.BOW,
                ItemKind.TRIDENT, ItemKind.CHESTPLATE, ItemKind.PICKAXE)) {
            assertFalse(kind.isUtility(), kind + " — не утилита");
        }
    }

    @Test
    void ordinaryItemsStayWithoutATier() {
        for (String material : List.of("APPLE", "DIRT", "WATER_BUCKET", "ELYTRA",
                "STICK", "DIAMOND", "BREAD")) {
            assertNull(ItemKind.classify(material), material + " — не тир-предмет");
        }
        assertNull(ItemKind.classify(null));
    }

    @Test
    void gearStaysClassifiedAsBefore() {
        assertEquals(ItemKind.SWORD, ItemKind.classify("NETHERITE_SWORD"));
        assertEquals(ItemKind.AXE, ItemKind.classify("IRON_AXE"));
        assertEquals(ItemKind.BOW, ItemKind.classify("BOW"));
        assertEquals(ItemKind.HELMET, ItemKind.classify("TURTLE_HELMET"));
        assertEquals(ItemKind.HELMET, ItemKind.classify("DIAMOND_HELMET"));
        assertEquals(ItemKind.BOOTS, ItemKind.classify("GOLDEN_BOOTS"));
    }
}
