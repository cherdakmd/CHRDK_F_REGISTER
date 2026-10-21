package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог навыков и перков: количество, иды, пороги и допустимые величины.
 *
 * <p>Тест держит инварианты, на которых стоит баланс: десять навыков, по шесть
 * перков, ровно пять порогов на навык, суммарные эффекты не выходят за
 * заявленные потолки.</p>
 */
class SkillRegistryTest {

    @Test
    void tenSkillsWithSixPerksEach() {
        assertEquals(10, SkillRegistry.size());
        assertEquals(SkillRegistry.EXPECTED_SKILLS, SkillRegistry.size());
        int perks = 0;
        for (SkillType skill : SkillRegistry.all()) {
            List<SkillPerk> list = SkillRegistry.perksOf(skill);
            assertEquals(SkillRegistry.PERKS_PER_SKILL, list.size(), "перков у " + skill.id());
            perks += list.size();
        }
        assertEquals(60, perks);
    }

    @Test
    void perksAreUniqueAndOrdered() {
        Set<String> ids = new HashSet<>();
        for (SkillType skill : SkillRegistry.all()) {
            int previous = 0;
            for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
                assertTrue(ids.add(perk.id()), "повтор ида перка: " + perk.id());
                assertTrue(perk.id().startsWith(prefix(skill)),
                        "ид " + perk.id() + " не относится к " + skill.id());
                assertTrue(perk.level() >= previous, "пороги должны возрастать");
                previous = perk.level();
            }
        }
        assertEquals(60, ids.size());
    }

    @Test
    void thresholdsAreTenTwentyFiveFiftySeventyFiveHundred() {
        List<Integer> expected = List.of(10, 25, 50, 75, 100);
        for (SkillType skill : SkillRegistry.all()) {
            assertEquals(expected, SkillPerks.thresholds(skill), "пороги " + skill.id());
        }
    }

    @Test
    void categoriesAreBalanced() {
        assertEquals(5, SkillRegistry.byCategory(SkillType.Category.GATHER).size());
        assertEquals(3, SkillRegistry.byCategory(SkillType.Category.COMBAT).size());
        assertEquals(2, SkillRegistry.byCategory(SkillType.Category.CRAFT).size());
        List<SkillType> all = new ArrayList<>(SkillRegistry.byCategory(SkillType.Category.GATHER));
        all.addAll(SkillRegistry.byCategory(SkillType.Category.COMBAT));
        all.addAll(SkillRegistry.byCategory(SkillType.Category.CRAFT));
        assertEquals(SkillRegistry.all(), all, "категории должны покрывать все навыки");
    }

    @Test
    void everyKindIsUsedAndValuesAreSane() {
        Set<SkillPerk.Kind> used = new HashSet<>();
        for (SkillType skill : SkillRegistry.all()) {
            for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
                used.add(perk.kind());
                assertTrue(perk.value() > 0.0, "нулевой перк: " + perk.id());
                if (perk.kind().percent()) {
                    assertTrue(perk.value() <= 1.0, "доля больше 100 %: " + perk.id());
                }
            }
        }
        assertEquals(SkillPerk.Kind.values().length, used.size(),
                "каждый вид эффекта должен встречаться хотя бы раз");
    }

    @Test
    void cumulativeEffectsStayWithinDocumentedCeilings() {
        for (SkillType skill : SkillRegistry.all()) {
            SkillPerks.Active active = SkillPerks.active(java.util.Map.of(skill, 100));
            double breakSpeed = active.value(SkillPerk.Kind.BREAK_SPEED);
            assertTrue(breakSpeed <= 0.20 + 1e-9, "копание выше 20 %: " + breakSpeed);
            assertTrue(active.value(SkillPerk.Kind.MELEE_DAMAGE) <= 0.15 + 1e-9);
            assertTrue(active.value(SkillPerk.Kind.DAMAGE_REDUCTION) <= 0.12 + 1e-9);
            assertTrue(active.value(SkillPerk.Kind.ARMOR) <= 6.0 + 1e-9);
            for (SkillPerk.Kind kind : SkillPerk.Kind.values()) {
                if (kind.percent()) {
                    assertTrue(active.value(kind) <= 1.0, "доля выше 1.0: " + kind);
                }
            }
        }
    }

    @Test
    void byIdAcceptsIdAndName() {
        assertNotNull(SkillType.byId("mining"));
        assertNotNull(SkillType.byId("MINING"));
        assertNotNull(SkillType.byId(" Mining "));
        assertNull(SkillType.byId("farming2"));
        assertNull(SkillType.byId(""));
        assertNull(SkillType.byId(null));
    }

    private static String prefix(SkillType skill) {
        return switch (skill) {
            case MINING -> "mining-";
            case WOODCUTTING -> "wood-";
            case DIGGING -> "dig-";
            case FARMING -> "farm-";
            case FISHING -> "fish-";
            case COMBAT -> "combat-";
            case ARCHERY -> "archery-";
            case DEFENSE -> "defense-";
            case ALCHEMY -> "alchemy-";
            case ENCHANTING -> "enchant-";
        };
    }
}
