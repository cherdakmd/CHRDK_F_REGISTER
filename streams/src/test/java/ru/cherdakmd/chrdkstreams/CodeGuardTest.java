package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Защита от перебора WATCH-кода: счёт ошибок, блокировка, сброс после успеха. */
class CodeGuardTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final int MAX = 3;
    private static final long LOCK = 5 * 60_000L;

    @Test
    void failuresAccumulate() {
        CodeGuard.State state = CodeGuard.State.empty();

        state = CodeGuard.failure(state, MAX, LOCK, NOW);
        assertEquals(1, state.failures());
        assertFalse(state.locked(NOW));
        assertEquals(2, CodeGuard.attemptsLeft(state, MAX));
    }

    @Test
    void thresholdLocksInput() {
        CodeGuard.State state = CodeGuard.State.empty();
        for (int attempt = 0; attempt < MAX; attempt++) {
            state = CodeGuard.failure(state, MAX, LOCK, NOW);
        }

        assertTrue(state.locked(NOW));
        assertEquals(NOW + LOCK, state.lockedUntil());
        assertEquals(LOCK, state.lockRemainingMillis(NOW));
        assertEquals(0, state.failures(), "после блокировки счёт начинается заново");
    }

    @Test
    void lockExpires() {
        CodeGuard.State state = CodeGuard.failure(
                new CodeGuard.State(MAX - 1, 0L), MAX, LOCK, NOW);

        assertTrue(state.locked(NOW + LOCK - 1));
        assertFalse(state.locked(NOW + LOCK));
        assertEquals(0L, state.lockRemainingMillis(NOW + LOCK));
    }

    @Test
    void successClearsEverything() {
        CodeGuard.State state = CodeGuard.success();

        assertEquals(0, state.failures());
        assertEquals(0L, state.lockedUntil());
        assertFalse(state.locked(NOW));
    }

    @Test
    void zeroAttemptsMeansNoLimit() {
        CodeGuard.State state = CodeGuard.failure(CodeGuard.State.empty(), 0, LOCK, NOW);

        assertFalse(state.locked(NOW));
        assertEquals(0, state.failures());
        assertEquals(Integer.MAX_VALUE, CodeGuard.attemptsLeft(state, 0));
    }
}
