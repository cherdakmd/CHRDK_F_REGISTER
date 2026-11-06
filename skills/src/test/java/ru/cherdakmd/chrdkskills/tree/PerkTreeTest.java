package ru.cherdakmd.chrdkskills.tree;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт обобщённого каркаса деревьев: пилот, запрет балансовых эффектов,
 * тир-гейты, зависимости, сброс и — главное — применимость к другому домену.
 */
class PerkTreeTest {

    private PerkTreeState funded(TreeDomain domain, int level, long cap) {
        return PerkTreeState.empty(domain).withLevel(level).creditPowder(5_000L, cap);
    }

    private PerkTreeState fundedMining(int level) {
        return funded(TreeDomain.MINING, level, PerkTreeRegistry.pilot().powderCap());
    }

    @Test
    void pilotIsValidAndStrictlyQol() {
        PerkTreeRegistry.validateAll();
        assertEquals(6, PerkTreeRegistry.defined().size());
        assertTrue(PerkTreeRegistry.defined().contains(TreeDomain.MINING));
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        assertEquals(TreeDomain.MINING, definition.domain());
        assertEquals(10, definition.tiers());
        assertEquals(20, definition.size());
        assertEquals(4, definition.abilities().size());
        assertEquals(1, definition.coreNodes().size());
        assertTrue(definition.nodes().stream().noneMatch(node -> node.effect().balanceSensitive()));
    }

    @Test
    void everyTreeIsTenTiersDeepWithTwoChoicesPerTier() {
        PerkTreeRegistry.validateAll();
        assertEquals(6, PerkTreeRegistry.defined().size());
        for (TreeDomain domain : PerkTreeRegistry.defined()) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            assertEquals(10, definition.tiers(), domain.id());
            assertEquals(20, definition.size(), domain.id());
            assertEquals(4, definition.abilities().size(), domain.id());
            assertEquals(1, definition.coreNodes().size(), domain.id());
            for (int tier = 1; tier <= 10; tier++) {
                assertEquals(2, definition.nodesAtTier(tier).size(),
                        domain.id() + ": тир " + tier + " должен давать выбор");
            }
            assertTrue(definition.nodes().stream()
                    .noneMatch(node -> node.effect().balanceSensitive()));
            assertTrue(definition.nodes().stream().anyMatch(node -> node.tokenCost() == 0),
                    domain.id() + ": нужен путь вложений только за пыль");
            assertTrue(definition.nodes().stream()
                    .anyMatch(node -> node.requires().size() >= 2),
                    domain.id() + ": ветви должны сходиться");
        }
    }

    @Test
    void newQolEffectsStayNonBalanceSensitive() {
        for (PerkEffect effect : List.of(
                PerkEffect.CRAFT_PREVIEW, PerkEffect.RECIPE_HINT,
                PerkEffect.ENCHANT_PREVIEW, PerkEffect.PRICE_HINT,
                PerkEffect.DEATH_RECAP, PerkEffect.EFFECT_TIMER,
                PerkEffect.WEATHER_HINT, PerkEffect.SPAWN_HINT,
                PerkEffect.LOOT_FILTER_HINT, PerkEffect.MAP_DETAIL,
                PerkEffect.TRACK_HINT, PerkEffect.ALLY_HUD,
                PerkEffect.TREE_GUIDE, PerkEffect.RESET_PREVIEW)) {
            assertFalse(effect.balanceSensitive(), effect + " должен оставаться QoL");
        }
        for (PerkEffect effect : List.of(PerkEffect.DROP_MULTIPLIER,
                PerkEffect.XP_MULTIPLIER, PerkEffect.BREAK_SPEED_MULTIPLIER)) {
            assertTrue(effect.balanceSensitive(), effect + " должен оставаться запрещённым");
        }
    }

    @Test
    void pveDomainsAreRegisteredAndStrictlyQol() {
        List<TreeDomain> pve = PerkTreeRegistry.pveDomains();
        assertEquals(5, pve.size());
        for (TreeDomain domain : pve) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            assertEquals(domain, definition.domain());
            assertFalse(definition.coreNodes().isEmpty(), "ядро у " + domain.id());
            assertFalse(definition.abilities().isEmpty(), "приём у " + domain.id());
            assertTrue(definition.nodes().stream().noneMatch(n -> n.effect().balanceSensitive()),
                    "PvE-дерево не должно давать урон, защиту, дроп или опыт: " + domain.id());
        }
        assertTrue(PerkTreeRegistry.defined().containsAll(pve));
        assertEquals(TreeDomain.COMBAT, PerkTreeRegistry.of(TreeDomain.COMBAT).orElseThrow().domain());
    }

    @Test
    void everyPveTreePassesTheSameUnlockAndResetContract() {
        for (TreeDomain domain : PerkTreeRegistry.pveDomains()) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            PerkNode first = definition.nodesAtTier(1).get(0);
            PerkTreeState opened = funded(domain, 20, definition.powderCap())
                    .unlock(definition, first.id());
            assertTrue(opened.isUnlocked(first.id()), domain.id());
            PerkTreeState reset = opened.reset();
            assertEquals(20, reset.level(), domain.id());
            assertEquals(0, reset.tokensSpent(), domain.id());
            assertTrue(reset.levels().isEmpty(), domain.id());
            assertEquals(definition.powderCap(), reset.powder(),
                    domain.id() + ": пыль за обычный узел возвращена полностью");
        }
    }

    @Test
    void reservedDomainsCoverOtherAspectsNotOnlyMining() {
        List<TreeDomain> reserved = PerkTreeRegistry.reserved();
        assertEquals(TreeDomain.values().length - PerkTreeRegistry.defined().size(), reserved.size());
        assertTrue(reserved.contains(TreeDomain.FISHING));
        assertTrue(reserved.contains(TreeDomain.FARMING));
        assertTrue(reserved.contains(TreeDomain.CRAFTING));
        assertTrue(reserved.contains(TreeDomain.COMMERCE));
        assertTrue(reserved.contains(TreeDomain.ALCHEMY));
        assertEquals(10, Arrays.stream(TreeDomain.values()).filter(TreeDomain::hasSkillSource).count());
        assertEquals(4, Arrays.stream(TreeDomain.values()).filter(d -> !d.hasSkillSource()).count());
        assertFalse(TreeDomain.FISHING.pilot());
        assertTrue(TreeDomain.MINING.pilot());
    }

    @Test
    void balanceSensitiveEffectsAreRejectedAtNodeCreation() {
        for (PerkEffect effect : List.of(PerkEffect.DROP_MULTIPLIER,
                PerkEffect.XP_MULTIPLIER, PerkEffect.BREAK_SPEED_MULTIPLIER)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PerkNode("bad", TreeDomain.MINING, 1, 1, 10, effect, 0.1),
                    "эффект " + effect + " должен быть запрещён");
        }
    }

    @Test
    void definitionRejectsBrokenDependenciesAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new PerkTreeDefinition(
                TreeDomain.FISHING, 2, 500L,
                List.of(new PerkNode("x", TreeDomain.FISHING, 1, 1, 10,
                        PerkEffect.HUD_DETAIL, 1.0, 1, Set.of("missing"), false, false))));
        PerkNode first = new PerkNode("same", TreeDomain.FISHING, 1, 1, 10, PerkEffect.HUD_DETAIL, 1.0);
        PerkNode second = new PerkNode("same", TreeDomain.FISHING, 1, 1, 10, PerkEffect.VEIN_PREVIEW, 1.0);
        assertThrows(IllegalArgumentException.class, () -> new PerkTreeDefinition(
                TreeDomain.FISHING, 2, 500L, List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> new PerkTreeDefinition(
                TreeDomain.FISHING, 1, 500L,
                List.of(new PerkNode("far", TreeDomain.FISHING, 3, 1, 10, PerkEffect.HUD_DETAIL, 1.0))));
    }

    @Test
    void tierGateAndTokensFollowDomainLevel() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        assertThrows(IllegalStateException.class,
                () -> fundedMining(0).unlock(definition, "mining-burst"),
                "второй тир закрыт на нулевом уровне");
        assertThrows(IllegalStateException.class,
                () -> fundedMining(10).unlock(definition, "mining-burst"),
                "на 10 уровне только один токен, приём стоит два");
        PerkTreeState state = fundedMining(20).unlock(definition, "mining-burst");
        assertTrue(state.isUnlocked("mining-burst"));
        assertEquals(1, state.levelOf("mining-burst"));
        assertEquals(0, state.tokensAvailable());
    }

    @Test
    void dependenciesAndPowderAreEnforcedBeforeAnyMutation() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        PerkTreeState state = fundedMining(30);
        assertThrows(IllegalStateException.class,
                () -> state.unlock(definition, "mining-hud"),
                "нужна подсветка жилы");
        PerkTreeState progressed = state.unlock(definition, "mining-vein-preview")
                .unlock(definition, "mining-hud");
        assertTrue(progressed.isUnlocked("mining-hud"));
        assertFalse(state.isUnlocked("mining-vein-preview"), "исходное состояние не изменилось");
        assertEquals(3, state.tokensAvailable(), "у исходного состояния все токены на месте");
        assertThrows(IllegalStateException.class,
                () -> PerkTreeState.empty(TreeDomain.MINING).withLevel(30)
                        .unlock(definition, "mining-vein-preview"),
                "без пыли покупка невозможна");
    }

    @Test
    void resetRefundsNonCorePowderAndTokensButKeepsLevelAndCore() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        PerkTreeState state = fundedMining(40)
                .unlock(definition, "mining-vein-preview")
                .unlock(definition, "mining-hud")
                .unlock(definition, "mining-core");
        assertEquals(1, state.coreLevel());
        assertEquals(440L, state.powderSpent());
        assertEquals(300L, state.corePowderSpent());
        long before = state.powder();

        PerkTreeState reset = state.reset();
        assertEquals(40, reset.level(), "уровень домена сохраняется");
        assertEquals(1, reset.coreLevel(), "ядро сохраняется");
        assertTrue(reset.levels().isEmpty());
        assertNull(reset.selectedAbility());
        assertEquals(0, reset.tokensSpent());
        assertEquals(before + 140L, reset.powder(), "вернулась пыль за два обычных узла");
        assertEquals(300L, reset.powderSpent(), "пыль ядра не возвращается");
        assertEquals(300L, reset.corePowderSpent());
    }

    @Test
    void onlyOneAbilityCanBeSelected() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        PerkTreeState state = fundedMining(20).unlock(definition, "mining-burst");
        PerkTreeState selected = state.selectAbility(definition, "mining-burst");
        assertEquals("mining-burst", selected.selectedAbility());
        assertThrows(IllegalArgumentException.class,
                () -> selected.selectAbility(definition, "mining-vein-preview"));
        assertThrows(IllegalStateException.class,
                () -> selected.selectAbility(definition, "mining-pickobulus"),
                "неоткрытый приём выбрать нельзя");
    }

    @Test
    void aSecondDomainUsesExactlyTheSameContract() {
        PerkTreeDefinition fishing = new PerkTreeDefinition(TreeDomain.FISHING, 2, 500L,
                List.of(new PerkNode("fish-preview", TreeDomain.FISHING, 1, 1, 20,
                                PerkEffect.HUD_DETAIL, 1.0),
                        new PerkNode("fish-core", TreeDomain.FISHING, 2, 1, 60,
                                PerkEffect.RESET_FEE_WAIVER, 1.0, 2,
                                Set.of("fish-preview"), true, false)));
        PerkTreeState state = funded(TreeDomain.FISHING, 20, fishing.powderCap())
                .unlock(fishing, "fish-preview")
                .unlock(fishing, "fish-core");
        assertEquals(1, state.coreLevel());
        assertEquals(420L, state.powder());
        PerkTreeState reset = state.reset();
        assertEquals(20, reset.level());
        assertEquals(1, reset.coreLevel());
        assertEquals(440L, reset.powder(), "вернулась пыль за обычный узел");
        assertEquals(60L, reset.powderSpent(), "пыль ядра удержана");
        assertTrue(reset.levels().isEmpty());
    }

    @Test
    void levelCostGrowsAndPowderCapIsNotExceeded() {
        PerkTreeDefinition definition = PerkTreeRegistry.pilot();
        PerkNode node = definition.node("mining-tool-care").orElseThrow();
        assertEquals(50L, PerkTreeMath.levelCost(node, 0));
        assertEquals(200L, PerkTreeMath.levelCost(node, 1));
        assertEquals(450L, PerkTreeMath.levelCost(node, 2));
        assertEquals(2_000L, definition.powderCap());
        assertEquals(0L, PerkTreeMath.creditedPowder(2_000L, 100L, 2_000L));
        assertEquals(50L, PerkTreeMath.creditedPowder(1_950L, 100L, 2_000L));
        assertEquals(0.15, PerkTreeMath.effectValue(node, 3), 1e-9);
    }
}
