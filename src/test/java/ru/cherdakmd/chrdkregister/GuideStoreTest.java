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
 * Память книги-путеводителя: новичок помечается при первом заходе, книга выдаётся один
 * раз, ветераны не получают её автоматически, битый файл не роняет плагин.
 */
class GuideStoreTest {

    private static final Logger LOGGER = Logger.getLogger("GuideStoreTest");

    @Test
    void pendingSurvivesRestart(@TempDir Path dir) {
        GuideStore store = new GuideStore(dir, LOGGER);
        store.load();
        store.markPending(GuideStore.key("Steve"));
        assertTrue(store.isPending("steve"));
        assertFalse(store.isGiven("steve"));

        GuideStore reloaded = new GuideStore(dir, LOGGER);
        reloaded.load();
        assertTrue(reloaded.isPending("steve"));
        assertEquals(1, reloaded.pendingCount());
    }

    @Test
    void markGivenClosesPending(@TempDir Path dir) {
        GuideStore store = new GuideStore(dir, LOGGER);
        store.load();
        store.markPending("alex");
        store.markGiven("alex");
        assertTrue(store.isGiven("alex"));
        assertFalse(store.isPending("alex"));

        GuideStore reloaded = new GuideStore(dir, LOGGER);
        reloaded.load();
        assertTrue(reloaded.isGiven("alex"));
        assertFalse(reloaded.isPending("alex"));
    }

    @Test
    void markPendingForKnownPlayerDoesNothing(@TempDir Path dir) {
        GuideStore store = new GuideStore(dir, LOGGER);
        store.load();
        store.markGiven("hero");
        store.markPending("hero");
        assertTrue(store.isGiven("hero"));
        assertFalse(store.isPending("hero"), "книга выдаётся один раз");
    }

    @Test
    void keyIgnoresCaseAndSpaces() {
        assertEquals("steve", GuideStore.key("  Steve "));
        assertEquals("steve", GuideStore.key("STEVE"));
        assertEquals("", GuideStore.key(null));
    }

    @Test
    void brokenFileStartsFromScratch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("guide-data.json"), "{oops");
        GuideStore store = new GuideStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.givenCount());
        store.markGiven("steve");
        assertTrue(store.isGiven("steve"));
    }

    @Test
    void playerInBothSetsCountsAsGiven(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("guide-data.json"),
                "{\"version\":1,\"given\":[\"old\"],\"pending\":[\"old\",\"new\"]}");
        GuideStore store = new GuideStore(dir, LOGGER);
        store.load();
        assertTrue(store.isGiven("old"));
        assertFalse(store.isPending("old"), "given важнее pending");
        assertTrue(store.isPending("new"));
    }
}
