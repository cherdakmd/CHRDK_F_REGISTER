package ru.cherdakmd.chrdkclasses;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

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
    void secondAbilityHasItsOwnCooldownAndSlot() {
        for (PlayerClass playerClass : ClassCatalog.classes()) {
            ClassAbility first = ClassAbility.of(playerClass, ClassAbility.FIRST);
            ClassAbility second = ClassAbility.of(playerClass, ClassAbility.SECOND);
            assertNotNull(first, playerClass.id() + ": нет первого умения");
            assertNotNull(second, playerClass.id() + ": нет второго умения");
            assertEquals(ClassAbility.PER_CLASS, ClassAbility.both(playerClass).size());
            assertEquals(playerClass.id(), first.key(), "ключ первого — ид класса");
            assertEquals(playerClass.id() + "-2", second.key(), "ключ второго — ид-2");
            assertTrue(second.cooldownSeconds() > first.cooldownSeconds(),
                    playerClass.id() + ": второе умение должно быть реже первого");
            assertTrue(second.cooldownSeconds() >= 30 && second.cooldownSeconds() <= 45,
                    playerClass.id() + ": откат второго вне 30..45 секунд");
        }
        assertEquals(ClassAbility.FIRST, ClassAbility.of(PlayerClass.MAGE, 99).slot(),
                "мусор в слоте читается как первое умение");
    }

    @Test
    void rankFactorShortensCooldownButNotBelowASecond() {
        ClassAbility ability = ClassAbility.WARRIOR;
        assertEquals(20_000L, ability.cooldownMillis(1.0, 1.0));
        assertEquals(16_000L, ability.cooldownMillis(1.0, 0.8), "ранг V: −20 %");
        assertEquals(20_000L, ability.cooldownMillis(1.0, 5.0),
                "множитель больше единицы не удлиняет откат");
        assertEquals(1_000L, ability.cooldownMillis(0.5, 0.0),
                "откат не может стать нулевым");
    }

    @Test
    void absorptionAndConeAreSane() {
        assertEquals(0, AbilityService.absorptionAmplifier(4.0), "4 полусердца — первый уровень");
        assertEquals(1, AbilityService.absorptionAmplifier(8.0), "8 полусердец — второй уровень");
        assertEquals(0, AbilityService.absorptionAmplifier(0.0), "пустая величина — первый уровень");
        assertEquals(3, AbilityService.absorptionAmplifier(999.0), "потолок — четвёртый уровень");

        Vector forward = new Vector(0, 0, 1);
        assertTrue(AbilityService.inCone(forward, new Vector(0, 0, 5), 35.0), "прямо впереди");
        assertTrue(AbilityService.inCone(forward, new Vector(1, 0, 4), 35.0), "чуть сбоку");
        assertTrue(!AbilityService.inCone(forward, new Vector(0, 0, -5), 35.0), "сзади не бьём");
        assertTrue(!AbilityService.inCone(forward, new Vector(5, 0, 0), 35.0), "в стороне не бьём");
        assertTrue(!AbilityService.inCone(null, forward, 35.0));
        assertTrue(!AbilityService.inCone(forward, new Vector(), 35.0));
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
    void bundledMessagesDescribeBothAbilities() throws Exception {
        Logger logger = Logger.getLogger("ClassAbilityTest");
        try (InputStream stream = ClassAbilityTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            Messages messages = Messages.from(stream, null, logger);
            for (ClassAbility ability : ClassAbility.values()) {
                for (String part : new String[]{"name", "desc"}) {
                    String key = "ability." + ability.key() + "." + part;
                    assertTrue(messages.has(key), "нет текста умения: " + key);
                    assertTrue(!messages.plain(key).isBlank(), "пустой текст: " + key);
                }
            }
            assertTrue(messages.has("menu-ability-second-name"),
                    "у второй кнопки меню должно быть своё имя");
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
