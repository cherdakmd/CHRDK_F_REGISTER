package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Атрибутная таблица волны 18: светлые чары дают плюс, проклятия — минус,
 * событийные чары атрибутов не имеют, уровни растут по таблице.
 */
class EnchantAttributeMapTest {

    @Test
    void keenEdgeScalesWithLevel() {
        List<EnchantAttributeMap.Modifier> first = EnchantAttributeMap.modifiers("keen_edge", 1);
        assertEquals(1, first.size());
        assertEquals(EnchantAttributeMap.Target.ATTACK_DAMAGE, first.get(0).target());
        assertEquals(EnchantAttributeMap.Kind.PERCENT, first.get(0).kind());
        assertEquals(0.04, first.get(0).amount(), 1e-9);
        assertEquals(0.08, EnchantAttributeMap.modifiers("keen_edge", 3).get(0).amount(), 1e-9);
        // выше максимума — держим последнюю ступень, а не улетаем за массив
        assertEquals(0.08, EnchantAttributeMap.modifiers("keen_edge", 9).get(0).amount(), 1e-9);
    }

    @Test
    void cursesAlwaysNegative() {
        for (String id : List.of("dull_blade", "clumsy_swing", "thin_padding", "heavy_hull")) {
            List<EnchantAttributeMap.Modifier> modifiers = EnchantAttributeMap.modifiers(id, 1);
            assertEquals(1, modifiers.size(), id);
            assertTrue(modifiers.get(0).amount() < 0, id + " должен быть минусом");
        }
    }

    @Test
    void defensiveEnchantsTouchArmorAndSpeed() {
        assertEquals(EnchantAttributeMap.Target.ARMOR,
                EnchantAttributeMap.modifiers("bulwark", 2).get(0).target());
        assertEquals(EnchantAttributeMap.Target.MOVEMENT_SPEED,
                EnchantAttributeMap.modifiers("featherstep", 1).get(0).target());
        assertEquals(EnchantAttributeMap.Target.MOVEMENT_SPEED,
                EnchantAttributeMap.modifiers("heavy_hull", 3).get(0).target());
    }

    @Test
    void eventOnlyEnchantsHaveNoAttributes() {
        // «Живучесть», «Мародёр», «Проклятая кровь» живут в слушателях и тиках.
        assertTrue(EnchantAttributeMap.modifiers("vitality", 3).isEmpty());
        assertTrue(EnchantAttributeMap.modifiers("plunder", 3).isEmpty());
        assertTrue(EnchantAttributeMap.modifiers("cursed_blood", 3).isEmpty());
        assertFalse(EnchantAttributeMap.hasModifiers("vitality"));
        assertTrue(EnchantAttributeMap.hasModifiers("bulwark"));
    }

    @Test
    void unknownOrZeroLevelIsEmpty() {
        assertTrue(EnchantAttributeMap.modifiers("no_such", 1).isEmpty());
        assertTrue(EnchantAttributeMap.modifiers(null, 1).isEmpty());
        assertTrue(EnchantAttributeMap.modifiers("keen_edge", 0).isEmpty());
        assertTrue(EnchantAttributeMap.modifiers("keen_edge", -2).isEmpty());
    }

    @Test
    void everyModifierHasOwnKeySuffix() {
        for (String id : List.of("keen_edge", "bulwark", "featherstep", "dull_blade",
                "clumsy_swing", "thin_padding", "heavy_hull")) {
            assertEquals(id, EnchantAttributeMap.modifiers(id, 1).get(0).suffix(),
                    "суффикс ключа модификатора должен быть уникален для чары");
        }
    }
}
