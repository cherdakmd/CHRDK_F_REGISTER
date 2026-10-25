package ru.cherdakmd.chrdkachievements;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementRulesTest {

    @Test
    void percentIsClampedAndRoundedDown() {
        assertEquals(0, AchievementRules.percent(0, 100));
        assertEquals(0, AchievementRules.percent(0, 0));
        assertEquals(50, AchievementRules.percent(50, 100));
        assertEquals(33, AchievementRules.percent(100, 300));
        assertEquals(100, AchievementRules.percent(500, 100));
        assertEquals(0, AchievementRules.percent(-5, 100));
    }

    @Test
    void completedAtThresholdOnly() {
        assertFalse(AchievementRules.completed(99, 100));
        assertTrue(AchievementRules.completed(100, 100));
        assertTrue(AchievementRules.completed(101, 100));
        assertFalse(AchievementRules.completed(1, 0), "нулевой порог не считается выполненным");
    }

    @Test
    void displayValueNeverExceedsGoal() {
        assertEquals(0, AchievementRules.displayValue(-3, 100));
        assertEquals(42, AchievementRules.displayValue(42, 100));
        assertEquals(100, AchievementRules.displayValue(1_000, 100));
    }

    @Test
    void newlyCompletedSkipsUnlockedAndSortsByCatalogue() {
        List<AchievementDef> catalogue = List.of(
                AchievementRegistry.byId("ohotnik"),
                AchievementRegistry.byId("opytnyj-ohotnik"),
                AchievementRegistry.byId("groza-mobov"));
        Map<AchievementMetric, Long> values = new LinkedHashMap<>();
        values.put(AchievementMetric.MOBS_KILLED, 1_000L);

        List<AchievementDef> fresh = AchievementRules.newlyCompleted(catalogue, Set.of(),
                metric -> values.getOrDefault(metric, 0L));
        assertEquals(2, fresh.size(), "1 000 мобов закрывает пороги 100 и 1 000, но не 5 000");
        assertEquals("ohotnik", fresh.get(0).id());
        assertEquals("opytnyj-ohotnik", fresh.get(1).id());

        List<AchievementDef> afterUnlock = AchievementRules.newlyCompleted(catalogue,
                Set.of("ohotnik", "opytnyj-ohotnik"), metric -> values.getOrDefault(metric, 0L));
        assertTrue(afterUnlock.isEmpty());
    }

    @Test
    void newlyCompletedHandlesFlagsAndEmptyInput() {
        List<AchievementDef> social = List.of(
                AchievementRegistry.byId("nadezhnyj-akkaunt"),
                AchievementRegistry.byId("dvuhfaktornyj"));
        List<AchievementDef> fresh = AchievementRules.newlyCompleted(social, Set.of(),
                metric -> metric == AchievementMetric.REGISTERED ? 1L : 0L);
        assertEquals(1, fresh.size());
        assertEquals("nadezhnyj-akkaunt", fresh.get(0).id());

        assertTrue(AchievementRules.newlyCompleted(null, Set.of(), metric -> 0L).isEmpty());
        assertTrue(AchievementRules.newlyCompleted(social, Set.of(), null).isEmpty());
    }

    @Test
    void barHasFixedWidth() {
        assertEquals("░░░░░░░░░░", AchievementRules.bar(0, 10));
        assertEquals("██████░░░░", AchievementRules.bar(60, 10));
        assertEquals("██████████", AchievementRules.bar(100, 10));
        assertEquals(10, AchievementRules.bar(150, 10).length());
        assertEquals(1, AchievementRules.bar(50, 0).length());
    }

    @Test
    void metricIdsRoundTrip() {
        for (AchievementMetric metric : AchievementMetric.values()) {
            assertEquals(metric, AchievementMetric.byId(metric.id()));
            assertEquals(metric, AchievementMetric.byId(metric.name()));
            assertEquals(metric, AchievementMetric.byId(metric.id().toUpperCase(java.util.Locale.ROOT)));
        }
        assertEquals(null, AchievementMetric.byId("unknown-metric"));
        assertEquals(null, AchievementMetric.byId(null));
        assertTrue(AchievementMetric.TWO_FACTOR.flag());
        assertFalse(AchievementMetric.MOBS_KILLED.flag());
    }
}
