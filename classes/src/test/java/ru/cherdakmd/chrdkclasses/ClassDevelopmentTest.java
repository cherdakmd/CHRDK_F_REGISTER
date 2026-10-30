package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Математика собственного прогресса без Bukkit, рангов и внешних API. */
class ClassDevelopmentTest {
    private static final long NOW = 1_792_000_000_000L;

    private static LegacyClassMigration report(int level, int earned) {
        return new LegacyClassMigration("warrior", level, earned,
                Set.of("warrior-stoyka-1", "warrior-stoyka-2"), NOW);
    }

    @Test
    void everyLevelHasExactAndMonotonicBoundaries() {
        long previousStep = 0;
        assertEquals(0, ClassDevelopmentRules.xpForLevel(1));
        for (int level = 2; level <= ClassDevelopmentRules.MAX_LEVEL; level++) {
            long threshold = ClassDevelopmentRules.xpForLevel(level);
            assertEquals(level, ClassDevelopmentRules.level(threshold));
            assertEquals(level - 1, ClassDevelopmentRules.level(threshold - 1));
            assertEquals(1, ClassDevelopmentRules.nextLevelIn(threshold - 1));
            long step = threshold - ClassDevelopmentRules.xpForLevel(level - 1);
            assertTrue(step > previousStep);
            previousStep = step;
        }
        assertEquals(0, ClassDevelopmentRules.nextLevelIn(ClassDevelopmentRules.MAX_XP));
        assertThrows(IllegalArgumentException.class, () -> ClassDevelopmentRules.level(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ClassDevelopmentRules.level(ClassDevelopmentRules.MAX_XP + 1));
        assertThrows(IllegalArgumentException.class, () -> ClassDevelopmentRules.xpForLevel(0));
        assertThrows(IllegalArgumentException.class, () -> ClassDevelopmentRules.xpForLevel(51));
    }

    @Test
    void newPlayerGetsOneVisibleStartingPoint() {
        ClassDevelopment state = ClassDevelopment.fresh();
        assertEquals(1, state.level());
        assertEquals(1, state.levelPoints());
        assertEquals(1, state.earnedPoints());
        assertEquals(0, state.legacyCredit());
        assertEquals(0, state.trialPoints());
        assertFalse(state.firstBuildAvailable());
        assertSame(state, state.consumeFirstBuild());
    }

    @Test
    void sourcesAccountForExactlyAcceptedXpAndCannotOverflow() {
        ClassDevelopment original = ClassDevelopment.fresh();
        ClassDevelopment state = original.awardXp(ClassDevelopment.Source.COMBAT, 100)
                .awardXp(ClassDevelopment.Source.SUPPORT, 50)
                .awardXp(ClassDevelopment.Source.EXPLORATION, Long.MAX_VALUE);
        assertEquals(0, original.xp(), "снимок не меняется задним числом");
        assertEquals(ClassDevelopmentRules.MAX_XP, state.xp());
        assertEquals(state.xp(), state.xpSources().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(100, state.xpSources().get(ClassDevelopment.Source.COMBAT));
        assertEquals(50, state.xpSources().get(ClassDevelopment.Source.SUPPORT));
        assertSame(state, state.awardXp(ClassDevelopment.Source.COMBAT, 1));
        assertSame(original, original.awardXp(ClassDevelopment.Source.COMBAT, 0));
        assertThrows(IllegalArgumentException.class,
                () -> original.awardXp(ClassDevelopment.Source.MIGRATION, 5));
        assertThrows(IllegalArgumentException.class,
                () -> original.awardXp(ClassDevelopment.Source.TRIAL, 5));
        assertThrows(IllegalArgumentException.class,
                () -> original.awardXp(ClassDevelopment.Source.COMBAT, -1));
        assertThrows(IllegalArgumentException.class, () -> original.awardXp(null, 1));
    }

    @Test
    void trialsGrantXpAndPointTogetherOnlyOnceAndHaveGlobalLimit() {
        ClassDevelopment state = ClassDevelopment.fresh();
        for (int i = 0; i < ClassDevelopmentRules.MAX_TRIAL_POINTS; i++) {
            ClassDevelopment before = state;
            state = state.completeTrial("trial_" + i, 20);
            assertEquals(before.revision() + 1, state.revision());
            assertSame(state, state.completeTrial("trial_" + i, Long.MAX_VALUE));
        }
        assertEquals(120, state.xp());
        assertEquals(6, state.trialPoints());
        assertSame(state, state.completeTrial("another_class_trial", 500));
        ClassDevelopment empty = ClassDevelopment.fresh();
        assertThrows(IllegalArgumentException.class, () -> empty.completeTrial("../trial", 20));
        assertThrows(IllegalArgumentException.class, () -> empty.completeTrial("trial", -1));
        assertThrows(IllegalArgumentException.class, () -> empty.completeTrial(null, 20));
    }

    @Test
    void trialStillAwardsPointAtXpCap() {
        ClassDevelopment state = ClassDevelopment.fresh()
                .awardXp(ClassDevelopment.Source.COMBAT, Long.MAX_VALUE).completeTrial("master", 100);
        assertEquals(ClassDevelopmentRules.MAX_XP, state.xp());
        assertEquals(51, state.earnedPoints());
    }

    @Test
    void migrationPreservesTotalNotEarnedPlusSpent() {
        LegacyClassMigration old = report(10, 12);
        assertEquals(3, old.spentPoints());
        assertEquals(9, old.freePoints());
        ClassDevelopment state = ClassDevelopment.migrate(old);
        assertEquals(10, state.level());
        assertEquals(12, state.earnedPoints(), "возврат — не добавление трёх очков к двенадцати");
        assertEquals(2, state.legacyCredit());
        assertEquals(ClassDevelopmentRules.xpForLevel(10), state.xp());
        assertTrue(state.firstBuildAvailable());
        assertEquals(old, state.migration());
        ClassDevelopment consumed = state.consumeFirstBuild();
        assertFalse(consumed.firstBuildAvailable());
        assertSame(consumed, consumed.consumeFirstBuild());
    }

    @Test
    void legacyCreditIsFloorNotCompoundingSource() {
        ClassDevelopment state = ClassDevelopment.migrate(report(10, 12));
        int previous = state.earnedPoints();
        for (int i = 0; i < 6; i++) {
            state = state.completeTrial("trial_" + i, 0);
            assertTrue(state.earnedPoints() >= previous);
            previous = state.earnedPoints();
        }
        assertEquals(16, state.earnedPoints(), "не 12 + 6: первые два очка уже сохранены переносом");
        state = state.awardXp(ClassDevelopment.Source.COMBAT, Long.MAX_VALUE);
        assertEquals(ClassDevelopmentRules.MAX_STANDARD_POINTS, state.earnedPoints());
        assertEquals(0, state.legacyCredit());
    }

    @Test
    void spentLegacySelectionIsNotLostWhenDerivedBudgetHasFallen() {
        ClassDevelopment state = ClassDevelopment.migrate(report(1, 1));
        assertEquals(3, state.earnedPoints());
        assertEquals(2, state.legacyCredit());
    }

    @Test
    void customOldBudgetIsPreservedWithoutBeingMultiplied() {
        ClassDevelopment state = ClassDevelopment.migrate(report(34, 177))
                .awardXp(ClassDevelopment.Source.COMBAT, Long.MAX_VALUE);
        for (int i = 0; i < 6; i++) {
            state = state.completeTrial("trial_" + i, 20);
        }
        assertEquals(177, state.earnedPoints());
        assertThrows(IllegalArgumentException.class, () -> report(34, 178));
        assertThrows(IllegalArgumentException.class, () -> report(35, 34));
    }

    @Test
    void frozenLegacyPricesMatchAllSixtyCurrentTalents() {
        int count = 0;
        for (PlayerClass playerClass : PlayerClass.values()) {
            for (Talent talent : ClassCatalog.talents(playerClass.id())) {
                assertEquals(talent.cost(), LegacyClassTalents.spent(playerClass.id(), Set.of(talent.id())));
                count++;
            }
        }
        assertEquals(60, count);
        assertThrows(IllegalArgumentException.class, () -> new LegacyClassMigration(
                "warrior", 1, 1, Set.of("mage-chary-1"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new LegacyClassMigration(
                "warrior", 1, 1, Set.of("lost-id"), NOW));
    }

    @Test
    void inputsAndViewsAreImmutable() {
        Map<ClassDevelopment.Source, Long> sources = new HashMap<>();
        sources.put(ClassDevelopment.Source.COMBAT, 10L);
        Set<String> trials = new HashSet<>();
        trials.add("trial");
        ClassDevelopment state = new ClassDevelopment(1, 10, sources, trials, null, false, 1);
        sources.clear();
        trials.clear();
        assertEquals(1, state.xpSources().size());
        assertEquals(1, state.trialAwards().size());
        assertThrows(UnsupportedOperationException.class, () -> state.xpSources().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.trialAwards().clear());
    }

    @Test
    void inconsistentSavedStateIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(2, 0, Map.of(), Set.of(), null, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(1, 10, Map.of(), Set.of(), null, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(1, 0, Map.of(), Set.of(), null, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(1, 10, Map.of(ClassDevelopment.Source.MIGRATION, 10L),
                        Set.of(), null, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(1, 0, Map.of(), Set.of(), null, false, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassDevelopment(1, 10, Map.of(ClassDevelopment.Source.TRIAL, 10L),
                        Set.of(), null, false, 0));
    }

    @Test
    void revisionOverflowCannotWrapAndGrantReward() {
        ClassDevelopment state = new ClassDevelopment(1, 0, Map.of(), Set.of(), null, false, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> state.awardXp(ClassDevelopment.Source.COMBAT, 1));
        assertThrows(ArithmeticException.class, () -> state.completeTrial("trial", 0));
        assertEquals(0, state.xp());
        assertTrue(state.trialAwards().isEmpty());
    }
}
