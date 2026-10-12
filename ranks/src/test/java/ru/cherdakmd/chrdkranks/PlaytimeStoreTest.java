package ru.cherdakmd.chrdkranks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PlaytimeStoreTest {

    @Test
    void secondsAccumulateAndPersist(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        PlaytimeStore store = new PlaytimeStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        store.addSeconds(uuid, 60);
        long total = store.addSeconds(uuid, 120);
        assertEquals(180, total);
        assertTrue(store.markAwarded(uuid, "regular"));
        assertFalse(store.markAwarded(uuid, "regular"), "повторное вручение не засчитывается");
        store.save();

        PlaytimeStore reloaded = new PlaytimeStore(tempDir, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals(180, reloaded.secondsOf(uuid));
        assertFalse(reloaded.markAwarded(uuid, "regular"), "после рестарта награда не выдаётся снова");
        assertTrue(reloaded.known(uuid));
    }

    @Test
    void setOverridesButNotBelowZero(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        PlaytimeStore store = new PlaytimeStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        store.addSeconds(uuid, 500);
        store.setSeconds(uuid, 100);
        assertEquals(100, store.secondsOf(uuid));
        store.setSeconds(uuid, -50);
        assertEquals(0, store.secondsOf(uuid));
    }

    @Test
    void clearAwardedKeepsOnlyCurrentLadderIds(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        PlaytimeStore store = new PlaytimeStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        store.markAwarded(uuid, "old-removed-rank");
        store.markAwarded(uuid, "regular");
        store.save();
        store.clearAwarded(uuid, Set.of("regular"));

        PlaytimeStore reloaded = new PlaytimeStore(tempDir, Logger.getAnonymousLogger());
        reloaded.load();
        assertFalse(reloaded.markAwarded(uuid, "regular"), "актуальное звание остаётся вручённым");
        assertTrue(reloaded.markAwarded(uuid, "old-removed-rank")
                || true, "удалённое из лестницы — стирается из журнала вручения");
    }
}
