package ru.cherdakmd.chrdkdaily;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стрик-логика: сутки UTC, продление подряд, сброс после пропуска, один claim в день.
 */
class DailyStoreTest {

    private static final long DAY = 86_400_000L;

    private DailyStore newStore(Path dir) {
        return new DailyStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void dayOfUtc() {
        assertEquals(0, DailyStore.dayOf(DAY - 1));
        assertEquals(1, DailyStore.dayOf(DAY));
        assertEquals(21520, DailyStore.dayOf(86_400_000L * 21_520L));
    }

    @Test
    void firstClaimStartsStreak(@TempDir Path dir) {
        DailyStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        long t0 = DAY * 21_500L + 1000;
        assertTrue(store.canClaim(id, t0));
        assertEquals(1, store.claim(id, t0));
        assertFalse(store.canClaim(id, t0), "дважды в один день нельзя");
        assertEquals(-1, store.claim(id, t0));
    }

    @Test
    void consecutiveDaysExtendStreak_gapResets(@TempDir Path dir) {
        DailyStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        long t0 = DAY * 21_500L + 1000;
        assertEquals(1, store.claim(id, t0));
        assertEquals(2, store.claim(id, t0 + DAY));
        assertEquals(3, store.claim(id, t0 + 2 * DAY));
        assertEquals(1, store.claim(id, t0 + 4 * DAY), "пропуск 3-го дня — стрик сброшен в 1");
        assertEquals(4, store.progressOf(id).totalClaims);
    }

    @Test
    void setStreakAdmin(@TempDir Path dir) {
        DailyStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        long t0 = DAY * 21_500L + 1000;
        store.claim(id, t0);
        store.setStreak(id, 10, t0);
        DailyStore.Progress progress = store.progressOf(id);
        assertEquals(10, progress.streak);
        assertFalse(store.canClaim(id, t0), "после set сегодняшняя считается взятой");
        assertEquals(11, store.claim(id, t0 + DAY), "завтра стрик продолжится с установленного");
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        DailyStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        long t0 = DAY * 21_500L + 1000;
        store.claim(id, t0);
        store.claim(id, t0 + DAY);
        store.saveNow();
        DailyStore reloaded = newStore(dir);
        reloaded.load();
        DailyStore.Progress progress = reloaded.progressOf(id);
        assertEquals(2, progress.streak);
        assertEquals(2, progress.totalClaims);
        assertEquals(DailyStore.dayOf(t0 + DAY), progress.lastClaimDay);
    }

    @Test
    void corruptedFileDoesNotCrashLoad(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("daily-data.json"), "[}");
        DailyStore store = newStore(dir);
        store.load();
        assertFalse(store.canClaim(UUID.randomUUID(), 1000), "повреждённый источник не переоткрывает награды");
    }
}
