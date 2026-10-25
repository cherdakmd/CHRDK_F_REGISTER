package ru.cherdakmd.chrdkclasses;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог умений классов (1.4.173): у каждого класса ровно одно умение,
 * кулдаун зажат границами конфига, а иконки не повторяют иконки классов.
 */
class ClassAbilityTest {

    @Test
    void everyClassHasExactlyOneAbility() {
        Set<String> ids = new HashSet<>();
        for (PlayerClass playerClass : ClassCatalog.classes()) {
            ClassAbility ability = ClassAbility.of(playerClass);
            assertNotNull(ability, "у класса " + playerClass.id() + " нет умения");
            assertEquals(playerClass, ability.playerClass());
            assertEquals(playerClass.id(), ability.id());
            assertTrue(ids.add(ability.id()), "умение повторяется: " + ability.id());
        }
        assertEquals(ClassCatalog.classes().size(), ids.size());
        assertNull(ClassAbility.of(null));
    }

    @Test
    void cooldownFollowsBinaryMultiplierAndStaysSane() {
        ClassAbility ability = ClassAbility.WARRIOR;
        assertEquals(20_000L, ability.cooldownMillis(1.0));
        assertEquals(10_000L, ability.cooldownMillis(0.5));
        assertEquals(60_000L, ability.cooldownMillis(3.0));
        // Мусор в конфиге не делает умение бесконечным или мгновенным.
        assertEquals(10_000L, ability.cooldownMillis(0.0));
        assertEquals(60_000L, ability.cooldownMillis(99.0));
        assertTrue(ability.cooldownMillis(1.0) > 0L);
    }

    @Test
    void remainingCooldownCountsDownAndSurvivesClockJump() {
        assertEquals(0L, ClassAbility.WARRIOR.remaining(0L, 1_000L, 20_000L), "не применяли");
        assertEquals(20_000L, ClassAbility.remaining(1_000L, 1_000L, 20_000L));
        assertEquals(5_000L, ClassAbility.remaining(1_000L, 16_000L, 20_000L));
        assertEquals(0L, ClassAbility.remaining(1_000L, 21_000L, 20_000L));
        assertEquals(20_000L, ClassAbility.remaining(10_000L, 5_000L, 20_000L),
                "часы ушли назад — умение не становится бесплатным");
        assertEquals(0L, ClassAbility.remaining(1_000L, 2_000L, 0L));
    }

    @Test
    void abilityNumbersStayInsideDesignCaps() {
        for (ClassAbility ability : ClassAbility.values()) {
            assertTrue(ability.cooldownSeconds() >= 15 && ability.cooldownSeconds() <= 30,
                    ability.id() + ": кулдаун вне 15..30 секунд");
            assertTrue(ability.power() > 0.0, ability.id() + ": сила должна быть положительной");
            if (ability == ClassAbility.MAGE || ability == ClassAbility.CLERIC) {
                assertTrue(ability.radius() > 0.0, ability.id() + ": нужен радиус");
            }
            if (ability == ClassAbility.CLERIC) {
                assertTrue(ability.power() <= 8.0, "жрец не должен лечить как зелье");
            }
            if (ability == ClassAbility.MAGE) {
                assertTrue(ability.power() <= 4.0, "маг не должен бить как меч");
            }
        }
    }

    @Test
    void iconsDifferFromClassIcons() {
        for (ClassAbility ability : ClassAbility.values()) {
            assertNotNull(ability.iconName());
            assertTrue(!ability.iconName().isBlank(), ability.id() + ": пустая иконка");
            assertTrue(!ability.iconName().equals(ability.playerClass().iconName()),
                    ability.id() + ": иконка умения совпадает с иконкой класса");
        }
    }

    @Test
    void volleyRotationKeepsLengthAndTurnsAside() {
        Vector base = new Vector(0, 0, 1);
        Vector turned = AbilityService.rotateY(base, 90);
        assertEquals(1.0, turned.length(), 1.0E-6);
        assertTrue(turned.getX() < -0.99 || turned.getX() > 0.99, "вектор должен повернуться");
        Vector zero = AbilityService.rotateY(null, 45);
        assertEquals(0.0, zero.length(), 1.0E-6);
    }
}
