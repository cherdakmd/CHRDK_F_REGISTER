package ru.cherdakmd.chrdkregister.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Модель общих источников и границы владения, без серверного реестра PotionEffectType. */
class PassivePotionTest {
    @Test
    void removingOneNightVisionSourceKeepsTheOthers() {
        var frames = new PassivePotionFrames<String, String, String, Integer>();
        frames.replace("player", "artifact", Map.of("night", 0), 100);
        frames.replace("player", "forge", Map.of("night", 0, "haste", 1), 100);
        frames.replace("player", "artifact", Map.of(), 100);
        assertEquals(Map.of("night", 0, "haste", 1), frames.desired("player", 50, source -> true, Math::max));
        frames.replace("player", "forge", Map.of(), 100);
        assertTrue(frames.desired("player", 50, source -> true, Math::max).isEmpty());
        assertTrue(frames.players().isEmpty());
    }

    @Test
    void completeFramesRemoveMissingEffectsAndKeepHighestNotSumOfLevels() {
        var frames = new PassivePotionFrames<String, String, String, Integer>();
        frames.replace("p", "base", Map.of("speed", 1, "night", 0), 100);
        frames.replace("p", "traits", Map.of("speed", 2), 100);
        assertEquals(2, frames.desired("p", 1, source -> true, Math::max).get("speed"));
        frames.replace("p", "base", Map.of("speed", 0), 100);
        assertEquals(Map.of("speed", 2), frames.desired("p", 1, source -> true, Math::max));
        frames.removeSources(source -> source.equals("traits"));
        assertEquals(Map.of("speed", 0), frames.desired("p", 1, source -> true, Math::max));
    }

    @Test
    void expiredDisabledAndOtherPlayerSourcesAreIsolated() {
        var frames = new PassivePotionFrames<String, String, String, Integer>();
        frames.replace("p", "dead-source", Map.of("night", 0), 20);
        frames.replace("p", "disabled", Map.of("speed", 0), 100);
        frames.replace("other", "alive", Map.of("night", 0), 100);
        assertTrue(frames.desired("p", 20, source -> !source.equals("disabled"), Math::max).isEmpty());
        assertEquals(Set.of("other"), frames.players());
        frames.remove("other");
        assertTrue(frames.players().isEmpty());
    }

    @Test
    void snapshotDoesNotFollowLaterChangesInCallerMap() {
        var frames = new PassivePotionFrames<String, String, String, Integer>();
        var supplied = new HashMap<>(Map.of("night", 0));
        frames.replace("p", "source", supplied, 100);
        supplied.clear();
        assertEquals(Map.of("night", 0), frames.desired("p", 1, source -> true, Math::max));
        frames.clear();
        assertTrue(frames.players().isEmpty());
    }

    @Test
    void nightVisionAlwaysRenewsBeforeClientBlinkThresholdWithoutReachingZero() {
        int ticks = PassivePotionPolicy.DURATION;
        int refreshes = 0;
        for (int tick = 0; tick < 30_000; tick++) {
            if (ticks <= PassivePotionPolicy.REFRESH_AT) {
                ticks = PassivePotionPolicy.DURATION;
                refreshes++;
            }
            assertTrue(ticks > 200, "Клиент не должен входить в последние 10 секунд");
            ticks--;
        }
        assertTrue(refreshes > 50);
    }

    @Test
    void strongerLongerAndInfiniteForeignEffectsAreProtected() {
        assertTrue(PassivePotionPolicy.protectsExternal(2, 1, 0));
        assertTrue(PassivePotionPolicy.protectsExternal(0, -1, 1));
        assertTrue(PassivePotionPolicy.protectsExternal(0, 1200, 0));
        assertFalse(PassivePotionPolicy.protectsExternal(0, 100, 0));
        assertFalse(PassivePotionPolicy.protectsExternal(0, 1200, 1));
    }

    @Test
    void ownershipExpiresAndCannotClaimAnotherAmplifierOrDifferentDeadline() {
        assertTrue(PassivePotionPolicy.matches(1, 580, 1, 600, 20));
        assertFalse(PassivePotionPolicy.matches(0, 580, 1, 600, 20));
        assertFalse(PassivePotionPolicy.matches(1, 800, 1, 600, 20));
        assertFalse(PassivePotionPolicy.matches(1, -1, 1, 600, 20));
        assertFalse(PassivePotionPolicy.matches(1, 0, 1, 600, 600));
    }

    @Test
    void restoredPotionUsesRemainingTimeNotOriginalDuration() {
        assertEquals(350, PassivePotionPolicy.remaining(400, 50));
        assertEquals(0, PassivePotionPolicy.remaining(400, 500));
        assertEquals(-1, PassivePotionPolicy.remaining(-1, 10_000));
        assertFalse(PassivePotionPolicy.preferIncoming(2, 100, 1, 1200));
        assertTrue(PassivePotionPolicy.preferIncoming(0, 100, 1, 50));
        assertTrue(PassivePotionPolicy.preferIncoming(1, 100, 1, 200));
        assertFalse(PassivePotionPolicy.preferIncoming(1, -1, 1, 1200));
    }

    @Test
    void renewalKeepsRegenerationPhaseInsteadOfCreatingExtraHealingTicks() {
        for (int interval : new int[]{50, 25, 12, 6, 3, 1}) {
            for (int remaining = 1; remaining <= 300; remaining++) {
                int renewed = PassivePotionPolicy.renewalDuration(remaining, interval);
                assertTrue(renewed >= PassivePotionPolicy.DURATION);
                assertEquals(remaining % interval, renewed % interval);
            }
        }
    }
}
