package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Чистая логика плейсхолдеров: разбор ключа и формат значений. */
class PerkTreePlaceholdersTest {

    private static final PerkTreeState STATE = new PerkTreeState(
            TreeDomain.COMBAT, 50, 0, 500L, 50L, 0L, 1,
            Map.of("combat-threat-hint", 1, "combat-focus", 1), "combat-focus");

    @Test
    void primaryDomainKeysUseTheWholeTailAsField() {
        PerkTreePlaceholders.Parsed level = PerkTreePlaceholders.parse("tree_level");
        assertNull(level.domain());
        assertEquals("level", level.field());
        assertEquals("level", PerkTreePlaceholders.parse("tree").field());
        assertEquals("tokens_spent", PerkTreePlaceholders.parse("TREE_TOKENS_SPENT").field());
    }

    @Test
    void explicitDomainIsRecognisedBeforeTheField() {
        PerkTreePlaceholders.Parsed combat = PerkTreePlaceholders.parse("tree_combat_powder");
        assertEquals(TreeDomain.COMBAT, combat.domain());
        assertEquals("powder", combat.field());
        assertEquals(TreeDomain.MINING,
                PerkTreePlaceholders.parse("tree_mining_tokens").domain());
    }

    @Test
    void unrelatedOrUnknownKeysAreNotTreePlaceholders() {
        assertNull(PerkTreePlaceholders.parse("level"));
        assertNull(PerkTreePlaceholders.parse(null));
        assertEquals("",
                PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "unknown"));
        assertEquals("",
                PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "bogus_level"));
    }

    @Test
    void valuesArePlainStringsForTabAndScoreboard() {
        assertEquals("combat", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "domain"));
        assertEquals("50", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "level"));
        assertEquals("500", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "powder"));
        assertEquals("4", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "tokens"));
        assertEquals("1", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "tokens_spent"));
        assertEquals("2", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "nodes"));
        assertEquals("combat-focus",
                PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "ability"));
        assertEquals("true",
                PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "available"));
        assertEquals("6", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "tier"));
        assertEquals("10", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "tiers"));
        assertEquals("10", PerkTreePlaceholders.resolve(STATE, TreeDomain.COMBAT, "next_tier"));
        PerkTreeState maxed = PerkTreeState.empty(TreeDomain.COMBAT).withLevel(100);
        assertEquals("10", PerkTreePlaceholders.resolve(maxed, TreeDomain.COMBAT, "tier"));
        assertEquals("0", PerkTreePlaceholders.resolve(maxed, TreeDomain.COMBAT, "next_tier"));
    }

    @Test
    void unavailableTreeNeverProducesFalseConditions() {
        assertEquals("", PerkTreePlaceholders.resolve(null, null, "level"));
        assertEquals("", PerkTreePlaceholders.resolve(null, null, "domain"));
        assertEquals("false", PerkTreePlaceholders.resolve(null, null, "available"));
        PerkTreeState blank = PerkTreeState.empty(TreeDomain.COMBAT);
        assertEquals("", PerkTreePlaceholders.resolve(blank, TreeDomain.COMBAT, "ability"));
        assertEquals("0", PerkTreePlaceholders.resolve(blank, TreeDomain.COMBAT, "powder"));
    }
}
