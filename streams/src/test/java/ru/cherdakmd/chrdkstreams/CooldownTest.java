package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Кулдаун /stream check: первая попытка свободна, дальше — пауза, ноль — без ограничения. */
class CooldownTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void firstUseIsAlwaysAllowed() {
        assertEquals(0L, Cooldown.remainingMillis(0L, 30_000L, NOW));
    }

    @Test
    void cooldownCountsDown() {
        assertEquals(30_000L, Cooldown.remainingMillis(NOW, 30_000L, NOW));
        assertEquals(10_000L, Cooldown.remainingMillis(NOW, 30_000L, NOW + 20_000L));
        assertEquals(0L, Cooldown.remainingMillis(NOW, 30_000L, NOW + 30_000L));
        assertEquals(0L, Cooldown.remainingMillis(NOW, 30_000L, NOW + 120_000L));
    }

    @Test
    void zeroCooldownDisablesTheLimit() {
        assertEquals(0L, Cooldown.remainingMillis(NOW, 0L, NOW));
        assertEquals(0L, Cooldown.remainingMillis(NOW, -5L, NOW));
    }

    @Test
    void secondsAreRoundedUp() {
        assertEquals(1L, Cooldown.remainingSeconds(NOW, 30_000L, NOW + 29_500L));
        assertEquals(30L, Cooldown.remainingSeconds(NOW, 30_000L, NOW));
        assertEquals(0L, Cooldown.remainingSeconds(NOW, 30_000L, NOW + 30_000L));
    }
}
