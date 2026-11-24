package ru.cherdakmd.chrdkfishing;

import java.util.Map;
import java.util.Set;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkfishing.FishingModel.*;

class FishingRulesTest {
    @Test void locationIsOffByDefaultAndExplicitlyIgnoresAllConfiguredZones() {
        var rules = new FishingRules(false, false, Map.of("lake", Set.of("pike")), Map.of("river", Set.of("perch")));
        assertFalse(FishingRules.defaults().locationEnabled());
        var a = new Random(1234); var b = new Random(1234);
        for (int i = 0; i < 1000; i++) {
            var first = roll(a, 1, 1, rules, new FishingRules.Context("lake", "river", false, false));
            var second = roll(b, 1, 1, rules, new FishingRules.Context("other", "ocean", false, false));
            assertEquals(first, second);
        }
    }
    @Test void enabledZoneTakesPrecedenceAndMissingRaritiesAreReweighted() {
        var rules = new FishingRules(true, true, Map.of("lake", Set.of("pike")), Map.of("river", Set.of("perch")));
        var random = new Random(11);
        for (int i = 0; i < 500; i++) {
            assertEquals("pike", roll(random, 10, 100, rules, new FishingRules.Context("lake", "river", true, true)).speciesId());
            assertEquals("perch", roll(random, 1, 1, rules, new FishingRules.Context(null, "river", false, false)).speciesId());
        }
        assertTrue(rules.eligible("missing", "missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new FishingRules(true, true, Map.of("x", Set.of()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new FishingRules(true, true, Map.of("x", Set.of("ghost")), Map.of()));
    }
    @Test void climateBoostDoesNotMakeOtherFishUnavailable() {
        var rules = new FishingRules(true, true, Map.of("z", Set.of("pike", "burbot")), Map.of());
        var counts = new java.util.HashMap<String, Integer>(); var random = new Random(44);
        for (int i = 0; i < 10_000; i++) {
            String id = roll(random, 1, 1, rules, new FishingRules.Context("z", null, true, false)).speciesId();
            counts.merge(id, 1, Integer::sum);
        }
        assertTrue(counts.get("burbot") > counts.get("pike"));
        assertTrue(counts.get("pike") > 0);
    }
    @Test void skillLevelIsCheckedAndImprovesTrophyProbability() {
        var rules = FishingRules.defaults(); var ctx = new FishingRules.Context(null, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> roll(new Random(), 1, 0, rules, ctx));
        assertThrows(IllegalArgumentException.class, () -> roll(new Random(), 1, 101, rules, ctx));
        var random = new Random(519); int low = 0, high = 0;
        for (int i = 0; i < 50_000; i++) {
            if (roll(random, 1, 1, rules, ctx).size() == Size.TROPHY) low++;
            if (roll(random, 1, 100, rules, ctx).size() == Size.TROPHY) high++;
        }
        assertTrue(high > low + 1000);
    }
}
