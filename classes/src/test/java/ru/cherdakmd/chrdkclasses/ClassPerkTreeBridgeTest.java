package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkskills.tree.PerkNode;
import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMath;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Связка готового rework классов с каркасом PvE-деревьев: мост даёт только
 * уровень домена и не подменяет классовый XP, очки, сборки и откаты.
 */
class ClassPerkTreeBridgeTest {

    @Test
    void classLevelsAreScaledAndCapped() {
        assertEquals(50, ClassDevelopmentRules.MAX_LEVEL);
        assertEquals(0, ClassPerkTreeBridge.domainLevel(0));
        assertEquals(20, ClassPerkTreeBridge.domainLevel(10));
        assertEquals(100, ClassPerkTreeBridge.domainLevel(ClassDevelopmentRules.MAX_LEVEL));
        assertEquals(0, ClassPerkTreeBridge.domainLevel(-5));
        assertEquals(100, ClassPerkTreeBridge.domainLevel(999));
    }

    @Test
    void everyRoleHasAPrimaryPveDomain() {
        for (PlayerClass role : PlayerClass.values()) {
            TreeDomain primary = ClassPerkTreeBridge.primaryDomain(role);
            assertNotNull(primary, "у роли " + role.id() + " должен быть ведущий домен");
            assertEquals(2, ClassPerkTreeBridge.classDomains(role).size());
            assertTrue(ClassPerkTreeBridge.classDomains(role).contains(primary));
            assertTrue(ClassPerkTreeBridge.classDomains(role).stream()
                    .allMatch(ClassPerkTreeBridge::isClassSourced));
            assertTrue(PerkTreeRegistry.of(primary).isPresent(),
                    "ведущее дерево " + primary.id() + " зарегистрировано");
        }
        assertEquals(TreeDomain.COMBAT, ClassPerkTreeBridge.primaryDomain(PlayerClass.WARRIOR));
        assertEquals(TreeDomain.ARCHERY, ClassPerkTreeBridge.primaryDomain(PlayerClass.ARCHER));
        assertEquals(TreeDomain.DEFENSE, ClassPerkTreeBridge.primaryDomain(PlayerClass.CLERIC));
        assertNull(ClassPerkTreeBridge.primaryDomain(null));
        assertTrue(ClassPerkTreeBridge.classDomains(null).isEmpty());
    }

    @Test
    void externalDomainsDoNotGetClassProgress() {
        assertFalse(ClassPerkTreeBridge.isClassSourced(TreeDomain.EXPLORATION));
        assertFalse(ClassPerkTreeBridge.isClassSourced(TreeDomain.HUNT));
        assertFalse(ClassPerkTreeBridge.isClassSourced(TreeDomain.MINING));
        assertEquals(2, ClassPerkTreeBridge.externalDomains().size());
        assertThrows(IllegalArgumentException.class,
                () -> ClassPerkTreeBridge.initialState(TreeDomain.HUNT, 50));
        assertThrows(IllegalArgumentException.class,
                () -> ClassPerkTreeBridge.initialState(TreeDomain.MINING, 50));
        assertThrows(IllegalArgumentException.class,
                () -> ClassPerkTreeBridge.initialState(null, 50));
    }

    @Test
    void bridgeSuppliesLevelButNoCurrency() {
        PerkTreeState state = ClassPerkTreeBridge.initialState(TreeDomain.COMBAT, 25);
        assertEquals(50, state.level());
        assertEquals(0L, state.powder(), "мост не выдаёт пыль");
        assertEquals(0, state.tokensSpent(), "мост не тратит токены");
        assertEquals(0, state.coreLevel());
        assertEquals(5, state.tokensAvailable());
        assertEquals(6, PerkTreeMath.availableTier(state.level(), 7));
    }

    @Test
    void classSourcedTreeCanUnlockAndResetWithoutLosingClassLevel() {
        PerkTreeDefinition definition = PerkTreeRegistry.of(TreeDomain.COMBAT).orElseThrow();
        PerkNode first = definition.nodesAtTier(1).get(0);
        PerkTreeState state = ClassPerkTreeBridge.initialState(TreeDomain.COMBAT, 25)
                .creditPowder(500L, definition.powderCap());
        PerkTreeState opened = state.unlock(definition, first.id());
        assertTrue(opened.isUnlocked(first.id()));
        PerkTreeState reset = opened.reset();
        assertEquals(50, reset.level(), "сброс дерева не сбрасывает класс");
        assertEquals(0, reset.tokensSpent());
        assertTrue(reset.levels().isEmpty());
        assertEquals(500L, reset.powder(), "пыль за обычный узел возвращена");
    }

    @Test
    void pveTreesStayQolOnlyAfterClassBridge() {
        PerkTreeRegistry.validateAll();
        assertTrue(ClassPerkTreeBridge.pveTreesStayQolOnly(),
                "связка с классом не должна открыть урон, защиту, дроп или опыт");
    }
}
