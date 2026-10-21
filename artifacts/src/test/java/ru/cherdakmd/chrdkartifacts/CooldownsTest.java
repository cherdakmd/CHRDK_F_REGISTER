package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownsTest {

    private final Cooldowns cooldowns = new Cooldowns();
    private final UUID player = UUID.randomUUID();

    @Test
    void clockFlowsAndExpires() {
        long now = 1_000_000L;
        assertFalse(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now));
        cooldowns.start(player, "grozovoj-kolokol", now, 90);
        assertTrue(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now));
        assertEquals(90, cooldowns.remainingSeconds(player, "grozovoj-kolokol", now));
        assertEquals(45, cooldowns.remainingSeconds(player, "grozovoj-kolokol", now + 45_500));
        assertFalse(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now + 90_000));
        assertEquals(0, cooldowns.remainingSeconds(player, "grozovoj-kolokol", now + 90_000));
    }

    @Test
    void zeroCooldownIsNoopAndArtifactsAreIndependent() {
        long now = 5_000L;
        cooldowns.start(player, "porvannyj-kupon", now, 0);
        assertFalse(cooldowns.isCoolingDown(player, "porvannyj-kupon", now));

        cooldowns.start(player, "rog-tajgi", now, 45);
        assertFalse(cooldowns.isCoolingDown(player, "klyuch-strannika", now));
        assertFalse(cooldowns.isCoolingDown(UUID.randomUUID(), "rog-tajgi", now));
    }

    @Test
    void snapshotRestoresOnlyLiveEntries() {
        long now = System.currentTimeMillis();
        cooldowns.start(player, "grozovoj-kolokol", now, 90);
        Map<String, Long> snapshot = cooldowns.snapshot();
        assertTrue(snapshot.containsValue(now + 90_000L) || !snapshot.isEmpty());

        Cooldowns restored = new Cooldowns();
        // Просроченная запись не должна воскреснуть после перезапуска.
        restored.restore(Map.of(
                player + "|grozovoj-kolokol", now + 90_000L,
                player + "|staryj-artefakt", now - 5_000L));
        assertTrue(restored.isCoolingDown(player, "grozovoj-kolokol", now));
        assertFalse(restored.isCoolingDown(player, "staryj-artefakt", now));
    }

    @Test
    void pruneRemovesExpiredAndKeepsActive() {
        long now = 10_000L;
        cooldowns.start(player, "rog-tajgi", now, 45);
        cooldowns.start(player, "grozovoj-kolokol", now, 90);
        assertEquals(0, cooldowns.prune(now + 50_000L));
        assertEquals(1, cooldowns.prune(now + 60_000L));
        assertTrue(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now + 60_000L));
        assertFalse(cooldowns.isCoolingDown(player, "rog-tajgi", now + 60_000L));
    }

    @Test
    void clearPlayerTouchesOnlyThatPlayer() {
        long now = 500L;
        UUID other = UUID.randomUUID();
        cooldowns.start(player, "rog-tajgi", now, 45);
        cooldowns.start(other, "rog-tajgi", now, 45);
        assertEquals(1, cooldowns.clearPlayer(player));
        assertFalse(cooldowns.isCoolingDown(player, "rog-tajgi", now));
        assertTrue(cooldowns.isCoolingDown(other, "rog-tajgi", now));
        assertEquals(0, cooldowns.clearPlayer(null));
    }

    @Test
    void dirtyFlagTracksChanges() {
        Cooldowns fresh = new Cooldowns();
        assertFalse(fresh.isDirty());
        fresh.start(player, "rog-tajgi", 1L, 45);
        assertTrue(fresh.isDirty());
        fresh.markClean();
        assertFalse(fresh.isDirty());
    }

    @Test
    void clearResetsEverything() {
        long now = 100L;
        cooldowns.start(player, "grozovoj-kolokol", now, 90);
        cooldowns.clear();
        assertFalse(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now));
    }
}
