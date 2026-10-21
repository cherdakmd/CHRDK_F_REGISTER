package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionStoreTest {

    private static final Logger LOGGER = Logger.getLogger("CollectionStoreTest");
    private static final String PLAYER = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @TempDir
    Path dir;

    @Test
    void discoveryIsIdempotentAndCountsOnlyRealArtifacts() {
        CollectionStore store = new CollectionStore(dir, LOGGER);
        assertTrue(store.discover(PLAYER, "solnechny-obereg", 1000L));
        assertFalse(store.discover(PLAYER, "solnechny-obereg", 2000L),
                "повторный подбор не должен считаться новым открытием");
        assertTrue(store.discover(PLAYER, "rog-tajgi", 3000L));
        assertFalse(store.discover(PLAYER, "nesushchestvuyushchij-artefakt", 4000L),
                "ид вне каталога не засчитывается");

        assertEquals(2, store.count(PLAYER));
        assertTrue(store.isDiscovered(PLAYER, "solnechny-obereg"));
        assertFalse(store.isDiscovered(PLAYER, "rog-tajgi") == false);
        assertFalse(store.isDiscovered("другой-игрок", "solnechny-obereg"));
        assertEquals(Set.of("solnechny-obereg", "rog-tajgi"), store.discovered(PLAYER));
    }

    @Test
    void claimedRewardsSurviveReload() {
        CollectionStore store = new CollectionStore(dir, LOGGER);
        store.discover(PLAYER, "solnechny-obereg", 1L);
        assertTrue(store.markClaimed(PLAYER, 25));
        assertFalse(store.markClaimed(PLAYER, 25), "порог нельзя выдать дважды");
        store.flush();

        CollectionStore reloaded = new CollectionStore(dir, LOGGER);
        reloaded.load();
        assertEquals(Set.of(25), reloaded.claimed(PLAYER));
        assertEquals(1, reloaded.count(PLAYER));
        assertFalse(reloaded.isDirty());
    }

    @Test
    void removedCatalogEntriesDoNotInflateProgress() throws Exception {
        Path file = dir.resolve("collection.json");
        Files.writeString(file, """
                {"version":1,"players":{"%s":{"discovered":["solnechny-obereg",
                "udalennyj-artefakt"],"claimed":[],"firstFound":1,"lastFound":2}}}
                """.formatted(PLAYER).replace("\n", ""), StandardCharsets.UTF_8);

        CollectionStore store = new CollectionStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.count(PLAYER), "прогресс не может превышать каталог");
        assertTrue(store.discovered(PLAYER).contains("udalennyj-artefakt"),
                "старые записи сохраняются, чтобы вернувшийся артефакт не потерялся");
    }

    @Test
    void missingFileIsEmptyAndSaveIsSafe() {
        CollectionStore store = new CollectionStore(dir.resolve("nested"), LOGGER);
        store.load();
        assertEquals(0, store.count(PLAYER));
        store.discover(PLAYER, "rog-tajgi", 5L);
        store.saveNow();
        assertTrue(Files.isRegularFile(dir.resolve("nested").resolve("collection.json")));
        assertFalse(store.isDirty());
    }
}
