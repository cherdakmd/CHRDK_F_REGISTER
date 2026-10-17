package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

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
    void clearResetsEverything() {
        long now = 100L;
        cooldowns.start(player, "grozovoj-kolokol", now, 90);
        cooldowns.clear();
        assertFalse(cooldowns.isCoolingDown(player, "grozovoj-kolokol", now));
    }
}
