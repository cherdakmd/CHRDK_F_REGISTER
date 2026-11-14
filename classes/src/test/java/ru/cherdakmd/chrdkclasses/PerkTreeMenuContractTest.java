package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkskills.SkillsMenuApi;
import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMenuApi;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт единого меню: «дерево перков» и «навыки» связываются только
 * сервисными интерфейсами, без новой compile-зависимости и без второго окна.
 *
 * <p>Проверяется чистая часть: слот → id узла и результат клика. Ни один тест
 * не создаёт инвентарь, поэтому он работает в JVM без сервера.</p>
 */
class PerkTreeMenuContractTest {

    @Test
    void guiImplementsSkillsOwnedContract() {
        assertTrue(PerkTreeMenuApi.class.isAssignableFrom(PerkTreeGui.class),
                "CHRDK_Classes должен реализовывать контракт, объявленный в skills");
    }

    @Test
    void skillsDeclaresItsOwnMenuContract() {
        // Пятая вкладка — зелья опыта (2.0.6). Контракт намеренно проверяет
        // точное число: новая вкладка не должна появиться молча, чтобы /perktree
        // не открыл чужое окно.
        assertEquals(5, SkillsMenuApi.Tab.values().length);
        assertSame(SkillsMenuApi.Tab.SKILLS, SkillsMenuApi.Tab.valueOf("SKILLS"));
        assertSame(SkillsMenuApi.Tab.TREE, SkillsMenuApi.Tab.valueOf("TREE"));
        assertSame(SkillsMenuApi.Tab.TOP, SkillsMenuApi.Tab.valueOf("TOP"));
        assertSame(SkillsMenuApi.Tab.PRESTIGE, SkillsMenuApi.Tab.valueOf("PRESTIGE"));
        assertSame(SkillsMenuApi.Tab.POTIONS, SkillsMenuApi.Tab.valueOf("POTIONS"));
    }

    @Test
    void nodeSlotMapsToNodeIdWithoutPerPlayerView() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        assertEquals(definition.nodes().get(0).id(),
                PerkTreeGui.nodeIdAt(definition, 0, 10));
        assertEquals(definition.nodes().get(14).id(),
                PerkTreeGui.nodeIdAt(definition, 0, 30));
        int secondPageFirst = PerkTreeGui.pageCount(definition.size(), 15) > 1 ? 15 : -1;
        if (secondPageFirst >= 0 && secondPageFirst < definition.size()) {
            assertEquals(definition.nodes().get(secondPageFirst).id(),
                    PerkTreeGui.nodeIdAt(definition, 1, 10));
        }
        assertNull(PerkTreeGui.nodeIdAt(definition, 0, 0), "верхний ряд — вкладки, не узлы");
        assertNull(PerkTreeGui.nodeIdAt(definition, 0, 45), "нижний ряд — кнопки, не узлы");
        assertNull(PerkTreeGui.nodeIdAt(definition, 99, 10), "страница за границей пуста");
    }

    @Test
    void resultFactoriesCarryActionDomainAndPage() {
        PerkTreeMenuApi.Result repaint =
                PerkTreeMenuApi.Result.repaint(PerkTreeRegistry.pilot().domain(), 2);
        assertSame(PerkTreeMenuApi.Action.REPAINT, repaint.action());
        assertEquals(2, repaint.page());
        assertSame(PerkTreeMenuApi.Action.CLOSE,
                PerkTreeMenuApi.Result.close(null, 0).action());
        assertSame(PerkTreeMenuApi.Action.NONE,
                PerkTreeMenuApi.Result.none(null, 0).action());
    }
}
