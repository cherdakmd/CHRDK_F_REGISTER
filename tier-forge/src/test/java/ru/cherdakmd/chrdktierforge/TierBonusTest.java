package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раскладка бонусов тира по видам предметов. Главное правило 1.4.117:
 * инструменты не получают урон и скорость атаки — только скорость добычи.
 */
class TierBonusTest {

    @Test
    void weaponsGetDamageOnly() {
        for (ItemKind kind : List.of(ItemKind.SWORD, ItemKind.BOW,
                ItemKind.CROSSBOW, ItemKind.TRIDENT)) {
            assertEquals(List.of(TierBonus.ATTACK_DAMAGE), TierBonus.forKind(kind),
                    kind + " — оружие, только урон");
            assertTrue(TierBonus.hasCombatBonus(kind));
            assertFalse(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void toolsGetMiningWithoutAttack() {
        for (ItemKind kind : List.of(ItemKind.PICKAXE, ItemKind.SHOVEL, ItemKind.HOE)) {
            assertEquals(List.of(TierBonus.MINING_SPEED), TierBonus.forKind(kind),
                    kind + " — инструмент, только скорость добычи");
            assertFalse(TierBonus.hasCombatBonus(kind),
                    "инструменту урон от тира не нужен: " + kind);
            assertTrue(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void axeIsBothWeaponAndTool() {
        assertEquals(List.of(TierBonus.ATTACK_DAMAGE, TierBonus.MINING_SPEED),
                TierBonus.forKind(ItemKind.AXE));
        assertTrue(TierBonus.hasCombatBonus(ItemKind.AXE), "топор бьёт");
        assertTrue(TierBonus.hasMiningBonus(ItemKind.AXE), "топор рубит лес");
    }

    @Test
    void armorGetsDefenseAndTinySpeed() {
        for (ItemKind kind : List.of(ItemKind.HELMET, ItemKind.CHESTPLATE,
                ItemKind.LEGGINGS, ItemKind.BOOTS)) {
            assertEquals(List.of(TierBonus.ARMOR, TierBonus.ARMOR_TOUGHNESS,
                    TierBonus.MOVEMENT_SPEED), TierBonus.forKind(kind));
            assertFalse(TierBonus.hasCombatBonus(kind));
            assertFalse(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void unknownKindGetsNothing() {
        assertTrue(TierBonus.forKind(null).isEmpty());
        assertTrue(TierBonus.forKind(ItemKind.classify("APPLE")).isEmpty(),
                "яблоко — не экипировка");
        assertFalse(TierBonus.hasCombatBonus(null));
        assertFalse(TierBonus.hasMiningBonus(null));
    }
}
