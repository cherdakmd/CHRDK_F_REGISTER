package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPolicyTest {

    @Test
    void defaultsKeepOldBehaviour() {
        WorldPolicy policy = WorldPolicy.defaults();
        assertTrue(policy.allowsWeather("world"));
        assertTrue(policy.allowsTime("world_nether"));
    }

    @Test
    void switchesTurnEffectsOff() {
        WorldPolicy noWeather = new WorldPolicy(false, true, Set.of());
        assertFalse(noWeather.allowsWeather("world"));
        assertTrue(noWeather.allowsTime("world"));

        WorldPolicy noTime = new WorldPolicy(true, false, Set.of());
        assertTrue(noTime.allowsWeather("world"));
        assertFalse(noTime.allowsTime("world"));
    }

    @Test
    void worldListLimitsScope() {
        WorldPolicy policy = new WorldPolicy(true, true, Set.of("world", "world_nether"));
        assertTrue(policy.allowsWeather("world"));
        assertTrue(policy.allowsTime("WORLD_NETHER"), "имена миров регистронезависимы");
        assertFalse(policy.allowsWeather("world_the_end"));
        assertFalse(policy.allowsTime("world_the_end"));
    }

    @Test
    void emptyOrNullListMeansEveryWorld() {
        assertTrue(new WorldPolicy(true, true, Set.of()).allowsTime("anything"));
        assertTrue(new WorldPolicy(true, true, null).allowsWeather("anything"));
        assertFalse(new WorldPolicy(true, true, Set.of("world")).allowsTime(null));
    }
}
