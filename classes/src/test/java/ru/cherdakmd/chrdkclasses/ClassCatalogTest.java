package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог классов и деревьев (1.4.164).
 *
 * <p>Дерево — это баланс, а не настройка: здесь проверяется, что у каждого
 * класса ровно три ветки по пять ярусов, что родитель таланта существует и
 * стоит на ярус выше, что требования ссылаются на настоящие навыки, а сумма
 * талантов не превышает потолки видов эффектов. Иначе игрок вложил бы очки в
 * прибавку, которую потом молча обрежет потолок.</p>
 */
class ClassCatalogTest {

    @Test
    void fourClassesInDisplayOrder() {
        List<PlayerClass> classes = ClassCatalog.classes();
        assertEquals(4, classes.size());
        assertEquals(List.of(PlayerClass.WARRIOR, PlayerClass.ARCHER, PlayerClass.MAGE,
                PlayerClass.CLERIC), classes);
        assertEquals("warrior", PlayerClass.WARRIOR.id());
        assertEquals("BOW", PlayerClass.ARCHER.iconName());
        assertEquals(PlayerClass.MAGE, PlayerClass.parse("  MAGE "));
        assertNull(PlayerClass.parse("паладин"));
        assertNull(PlayerClass.parse(null));
        assertTrue(ClassCatalog.exists("cleric"));
        assertFalse(ClassCatalog.exists(""));
        assertNull(ClassCatalog.playerClass("неведомый"));
    }

    @Test
    void everyClassHasThreeBranchesOfFiveTiers() {
        for (PlayerClass playerClass : PlayerClass.values()) {
            List<Talent> talents = ClassCatalog.talents(playerClass.id());
            assertEquals(ClassCatalog.TALENTS_PER_CLASS, talents.size(),
                    "талантов у " + playerClass.id());
            List<String> branches = ClassCatalog.branches(playerClass.id());
            assertEquals(ClassCatalog.BRANCHES_PER_CLASS, branches.size());
            for (String branch : branches) {
                List<Talent> nodes = ClassCatalog.branch(playerClass.id(), branch);
                assertEquals(ClassMath.MAX_TIER, nodes.size(), "ветка " + branch);
                for (int index = 0; index < nodes.size(); index++) {
                    assertEquals(index + 1, nodes.get(index).tier(), "ярусы по порядку");
                    assertEquals(playerClass.id(), nodes.get(index).classId());
                    assertEquals(branch, nodes.get(index).branch());
                }
            }
        }
    }

    @Test
    void parentChainIsConsistent() {
        for (PlayerClass playerClass : PlayerClass.values()) {
            for (Talent talent : ClassCatalog.talents(playerClass.id())) {
                if (talent.tier() == 1) {
                    assertFalse(talent.hasParent(), "первый ярус без родителя: " + talent.id());
                    continue;
                }
                assertTrue(talent.hasParent(), "у яруса " + talent.tier() + " есть родитель");
                Talent parent = ClassCatalog.parentOf(talent);
                assertNotNull(parent, "родитель существует: " + talent.requires());
                assertEquals(talent.branch(), parent.branch(), "родитель в той же ветке");
                assertEquals(talent.tier() - 1, parent.tier(), "родитель на ярус выше");
            }
        }
        assertNull(ClassCatalog.parentOf(ClassCatalog.talent("warrior-stoyka-1")));
        assertNull(ClassCatalog.parentOf(null));
    }

    @Test
    void idsAndSkillsAreValid() {
        Set<String> ids = new HashSet<>();
        for (PlayerClass playerClass : PlayerClass.values()) {
            for (Talent talent : ClassCatalog.talents(playerClass.id())) {
                assertTrue(ids.add(talent.id()), "ид таланта уникален: " + talent.id());
                assertTrue(talent.cost() >= 1 && talent.cost() <= 10, "цена в разумных пределах");
                assertTrue(talent.tier() >= 1 && talent.tier() <= ClassMath.MAX_TIER);
                assertTrue(talent.value() > 0.0, "талант без эффекта бесполезен");
                assertNotNull(ClassCatalog.talent(talent.id()));
                if (talent.needsSkill()) {
                    assertTrue(ClassCatalog.SKILLS.contains(talent.skill()),
                            "навык известен: " + talent.skill());
                    assertTrue(talent.skillLevel() >= 1 && talent.skillLevel() <= 100);
                }
            }
        }
        assertNull(ClassCatalog.talent("нет-такого"));
        assertNull(ClassCatalog.talent(null));
        assertEquals(60, ids.size());
    }

    @Test
    void talentTotalsFitEffectCaps() {
        for (PlayerClass playerClass : PlayerClass.values()) {
            Map<EffectKind, Double> totals = new java.util.EnumMap<>(EffectKind.class);
            for (Talent talent : ClassCatalog.talents(playerClass.id())) {
                totals.merge(talent.kind(), talent.value(), Double::sum);
            }
            for (Map.Entry<EffectKind, Double> entry : ClassCatalog.passive(playerClass.id())
                    .entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            for (Map.Entry<EffectKind, Double> entry : totals.entrySet()) {
                assertTrue(entry.getValue() <= entry.getKey().cap() + 0.0001,
                        playerClass.id() + ": " + entry.getKey().id() + " = " + entry.getValue()
                                + " выше потолка " + entry.getKey().cap());
            }
        }
    }

    @Test
    void masteryTierIsACapstoneNotAStairStep() {
        int full = 0;
        int oldTree = 0;
        for (Talent talent : ClassCatalog.talents("warrior")) {
            full += talent.cost();
            if (talent.tier() < ClassMath.MAX_TIER) {
                oldTree += talent.cost();
            }
        }
        int points = ClassMath.points(ClassMath.level(1_000, 30), 1, 1);
        assertEquals(33, oldTree, "старое дерево: 1+2+3+5 в каждой из трёх веток");
        assertEquals(36, full, "пятый ярус добавляет по одному очку в ветку");
        assertEquals(34, points, "на потолке уровня доступно 34 очка");
        assertTrue(oldTree <= points, "дерево без мастерских узлов собирается целиком");
        assertTrue(oldTree + 1 <= points, "полное дерево и один мастерский узел — да");
        assertTrue(oldTree + 3 > points, "три мастерских узла — уже выбор, а не добавка");
    }

    @Test
    void masteryNodesNeedSeventyFiveSkill() {
        for (PlayerClass playerClass : PlayerClass.values()) {
            for (String branch : ClassCatalog.branches(playerClass.id())) {
                List<Talent> nodes = ClassCatalog.branch(playerClass.id(), branch);
                Talent last = nodes.get(nodes.size() - 1);
                assertEquals(ClassMath.MAX_TIER, last.tier());
                assertEquals(1, last.cost(), "мастерский узел стоит одно очко: " + last.id());
                assertTrue(last.needsSkill(), "мастерский узел требует навык: " + last.id());
                assertEquals(75, last.skillLevel(), "порог мастерского узла: " + last.id());
                assertNotNull(ClassCatalog.parentOf(last), "у мастерского узла есть родитель");
            }
        }
    }

    @Test
    void effectKindsAndUnitsAreStable() {
        assertEquals(10, EffectKind.values().length);
        assertEquals(EffectKind.MAX_HEALTH, EffectKind.parse("MAX_HEALTH"));
        assertEquals(EffectKind.MOVEMENT_SPEED, EffectKind.parse(" movement-speed "));
        assertNull(EffectKind.parse("мана"));
        assertEquals(EffectKind.Unit.PERCENT, EffectKind.MELEE_DAMAGE.unit());
        assertEquals(EffectKind.Unit.HALF_HEARTS, EffectKind.REGEN.unit());
        assertTrue(EffectKind.DAMAGE_REDUCTION.cap() <= 0.5, "снижение урона не съедает бой");
        assertTrue(EffectKind.MAX_HEALTH.cap() <= 10.0, "здоровье не удваивается");
    }
}
