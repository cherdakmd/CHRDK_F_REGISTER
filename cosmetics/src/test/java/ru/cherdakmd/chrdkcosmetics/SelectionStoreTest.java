package ru.cherdakmd.chrdkcosmetics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SelectionStoreTest {

    @Test
    void selectionPersistsAndClears(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        SelectionStore store = new SelectionStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        assertNull(store.selectionOf(uuid));
        store.select(uuid, "flame");
        store.save();

        SelectionStore reloaded = new SelectionStore(tempDir, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals("flame", reloaded.selectionOf(uuid));
        reloaded.select(uuid, null);
        assertNull(reloaded.selectionOf(uuid));
        assertTrue(reloaded.isDirty(), "снятие выбора тоже помечает хранилище грязным");
    }
}
