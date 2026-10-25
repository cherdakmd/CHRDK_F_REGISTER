package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownStoreTest {

    private static final Logger LOGGER = Logger.getLogger("CooldownStoreTest");
    private static final String KEY = "0f8fad5b-d9cb-469f-a165-70867728950e|grozovoj-kolokol";

    @TempDir
    Path dir;

    @Test
    void mapSurvivesSaveAndLoad() {
        CooldownStore store = new CooldownStore(dir, LOGGER);
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put(KEY, 1_700_000_000_000L);
        snapshot.put("игрок2|rog-tajgi", 1_700_000_090_000L);
        store.save(snapshot);

        CooldownStore reloaded = new CooldownStore(dir, LOGGER);
        assertEquals(snapshot, reloaded.load());
    }

    @Test
    void missingAndBrokenFilesYieldEmptyMap() throws Exception {
        CooldownStore missing = new CooldownStore(dir.resolve("nested"), LOGGER);
        assertTrue(missing.load().isEmpty());

        Files.writeString(dir.resolve("cooldowns.json"), "{ не json", StandardCharsets.UTF_8);
        CooldownStore broken = new CooldownStore(dir, LOGGER);
        assertTrue(broken.load().isEmpty());
    }

    @Test
    void nonPositiveAndNonNumericEntriesAreDropped() throws Exception {
        Files.writeString(dir.resolve("cooldowns.json"), """
                {"version":1,"cooldowns":{"%s":0,"bad":"текст","ok":99}}
                """.formatted(KEY).replace("\n", ""), StandardCharsets.UTF_8);
        CooldownStore store = new CooldownStore(dir, LOGGER);
        assertEquals(Map.of("ok", 99L), store.load());
    }

    @Test
    void saveCreatesParentDirectories() {
        CooldownStore store = new CooldownStore(dir.resolve("a").resolve("b"), LOGGER);
        store.save(Map.of(KEY, 123L));
        assertTrue(Files.isRegularFile(dir.resolve("a").resolve("b").resolve("cooldowns.json")));
    }
}
