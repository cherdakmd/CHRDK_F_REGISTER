package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Память случайного спавна: заявка первого входа переживает перезапуск, выданная точка
 * закрывает заявку навсегда, старые заявки чистятся, битый файл не роняет плагин.
 */
class SpawnStoreTest {

    private static final Logger LOGGER = Logger.getLogger("SpawnStoreTest");

    @Test
    void pendingSurvivesRestart(@TempDir Path dir) {
        SpawnStore store = new SpawnStore(dir, LOGGER);
        store.load();
        store.markPending(SpawnStore.key("Steve"), 1_000L);
        assertTrue(store.isPending("steve"));
        assertFalse(store.isDone("steve"));

        SpawnStore reloaded = new SpawnStore(dir, LOGGER);
        reloaded.load();
        assertTrue(reloaded.isPending("steve"), "заявка должна читаться с диска");
        assertEquals(1, reloaded.pendingCount());
    }

    @Test
    void markDoneClosesPendingForever(@TempDir Path dir) {
        SpawnStore store = new SpawnStore(dir, LOGGER);
        store.load();
        store.markPending("alex", 5L);
        store.markDone("alex");
        assertFalse(store.isPending("alex"));
        assertTrue(store.isDone("alex"));

        SpawnStore reloaded = new SpawnStore(dir, LOGGER);
        reloaded.load();
        assertTrue(reloaded.isDone("alex"));
        assertFalse(reloaded.isPending("alex"));
    }

    @Test
    void keyIgnoresCaseAndSpaces() {
        assertEquals("steve", SpawnStore.key("  Steve "));
        assertEquals("steve", SpawnStore.key("STEVE"));
        assertEquals("", SpawnStore.key(null));
    }

    @Test
    void markPendingForKnownPlayerDoesNothing(@TempDir Path dir) {
        SpawnStore store = new SpawnStore(dir, LOGGER);
        store.load();
        store.markDone("hero");
        store.markPending("hero", 1L);
        assertTrue(store.isDone("hero"));
        assertFalse(store.isPending("hero"), "игравшего повторно не рандомим");
    }

    @Test
    void pruneDropsStalePendingOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spawn-data.json");
        Files.writeString(file, """
                {"version":1,"done":["old"],"pending":{"stale":1000,"fresh":900000}}
                """);
        SpawnStore store = new SpawnStore(dir, LOGGER);
        store.load();

        int removed = store.prunePending(1_000_000L, 500_000L);

        assertEquals(1, removed);
        assertFalse(store.isPending("stale"));
        assertTrue(store.isPending("fresh"));
        assertTrue(store.isDone("old"));
    }

    @Test
    void brokenFileDoesNotThrow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("spawn-data.json"), "{not json");
        SpawnStore store = new SpawnStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.doneCount());
        store.markDone("steve");
        assertTrue(store.isDone("steve"));
    }
}
