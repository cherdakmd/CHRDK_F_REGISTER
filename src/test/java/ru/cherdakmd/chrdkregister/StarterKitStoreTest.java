package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Память выдачи: один раз на UUID, переживает рестарт и смену ника. */
final class StarterKitStoreTest {

    @TempDir Path dir;

    private StarterKitStore store() {
        return new StarterKitStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void pendingBecomesGivenOnlyOnceAndSurvivesReload() {
        UUID id = UUID.randomUUID();
        String key = StarterKitStore.key(id);
        StarterKitStore first = store();
        first.load();
        first.markPending(key);
        assertTrue(first.isPending(key));
        assertEquals(1, first.pendingCount());
        first.markGiven(key);
        assertFalse(first.isPending(key));
        assertTrue(first.isGiven(key));
        first.markPending(key); // «выдан» важнее «ожидает»
        assertFalse(first.isPending(key));
        assertEquals(1, first.givenCount());

        StarterKitStore second = store();
        second.load();
        assertTrue(second.isGiven(key), "флаг пережил рестарт");
        assertFalse(second.isPending(key));
        assertTrue(Files.exists(dir.resolve("starter-kits.json")));
        assertTrue(first.snapshotJson().contains("\"version\":1"));
        assertFalse(Files.exists(dir.resolve("starter-kits.json.tmp")), "temp убран");
    }

    @Test
    void corruptFileNeverGrantsTwiceAndStartsClean() throws Exception {
        Path file = dir.resolve("starter-kits.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        StarterKitStore store = store();
        store.load();
        assertEquals(0, store.givenCount());
        assertEquals(0, store.pendingCount());
        String key = StarterKitStore.key(UUID.randomUUID());
        store.markGiven(key);
        assertTrue(store.isGiven(key));
        StarterKitStore reloaded = store();
        reloaded.load();
        assertTrue(reloaded.isGiven(key));
    }

    @Test
    void keyIsUuidBasedAndCaseInsensitive() {
        UUID id = UUID.fromString("A0B1C2D3-0000-1111-2222-333344445555");
        assertEquals(id.toString().toLowerCase(), StarterKitStore.key(id));
        assertEquals("", StarterKitStore.key(null));
    }
}
