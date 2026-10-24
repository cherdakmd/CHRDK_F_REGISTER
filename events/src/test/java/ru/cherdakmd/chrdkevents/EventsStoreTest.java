package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * История мировых событий (1.4.160): запись, подсчёт, сохранение и чтение
 * {@code events.json}.
 */
class EventsStoreTest {

    private static final Logger LOGGER = Logger.getLogger("EventsStoreTest");

    private static WorldEvent event(String id) {
        return new WorldEvent(id, "Событие " + id, "", EventKind.XP, 300, 10, 120, 50,
                List.of(), List.of(), List.of(), Material.CLOCK);
    }

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.load();
        assertEquals(0L, store.nextAt());
        assertEquals(0, store.totalAll());
        assertEquals(0, store.total("e0"));
        assertTrue(store.history().isEmpty());
        assertNull(store.last());
        assertTrue(store.cooldowns().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void startAndStopAreRecorded(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        WorldEvent event = event("e0");
        store.recordStart(event, 1_000_000L, false);
        assertEquals(1, store.totalAll());
        assertEquals(1, store.total("e0"));
        EventsStore.Entry last = store.last();
        assertNotNull(last);
        assertTrue(last.open());
        assertFalse(last.forced());

        store.recordStop(event, 1_300_000L);
        last = store.last();
        assertNotNull(last);
        assertFalse(last.open());
        assertEquals(1_300_000L, last.ended());
        assertEquals(300, last.duration());
        assertEquals(1, store.total("e0"), "остановка не добавляет запуск");
    }

    @Test
    void startClosesThePreviousOpenEntry(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.recordStart(event("first"), 1_000_000L, false);
        store.recordStart(event("second"), 1_100_000L, true);
        List<EventsStore.Entry> history = store.history();
        assertEquals(2, history.size());
        assertFalse(history.get(0).open(), "прошлое событие закрыто при старте нового");
        assertEquals(100, history.get(0).duration());
        assertTrue(history.get(1).open());
        assertTrue(history.get(1).forced());
    }

    @Test
    void nextMomentAndCooldownsAreStored(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.setNextAt(5_000L);
        store.setCooldowns(Map.of("e0", 9_000L));
        assertTrue(store.isDirty());
        assertEquals(5_000L, store.nextAt());
        assertEquals(9_000L, store.cooldowns().get("e0"));
    }

    @Test
    void survivesReload(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.recordStart(event("e0"), 1_000_000L, false);
        store.recordStop(event("e0"), 1_100_000L);
        store.recordStart(event("e1"), 1_200_000L, true);
        store.setNextAt(2_000_000L);
        store.setCooldowns(Map.of("e0", 1_500_000L));
        store.flush();
        assertTrue(Files.isRegularFile(dir.resolve("events.json")));

        EventsStore restored = new EventsStore(dir, LOGGER);
        restored.load();
        assertEquals(2_000_000L, restored.nextAt());
        assertEquals(1_500_000L, restored.cooldowns().get("e0"));
        assertEquals(2, restored.totalAll());
        assertEquals(1, restored.total("e1"));
        assertEquals(2, restored.history().size());
        EventsStore.Entry last = restored.last();
        assertNotNull(last);
        assertEquals("e1", last.id());
        assertTrue(last.open(), "незакрытая запись переживает перезапуск");
    }

    @Test
    void saveNowWritesOnlyWhenDirty(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.saveNow();
        assertFalse(Files.exists(dir.resolve("events.json")), "чистый store не пишет файл");
        store.recordStart(event("e0"), 1_000L, false);
        store.saveNow();
        assertTrue(Files.isRegularFile(dir.resolve("events.json")));
    }

    @Test
    void brokenFileDoesNotThrow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("events.json"), "{это не json");
        EventsStore store = new EventsStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.totalAll());
        assertTrue(store.history().isEmpty());
    }

    @Test
    void historyIsCapped(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        for (int index = 0; index < EventsStore.MAX_HISTORY + 10; index++) {
            store.recordStart(event("e" + index), 1_000L + index, false);
        }
        assertEquals(EventsStore.MAX_HISTORY, store.history().size());
        EventsStore.Entry last = store.last();
        assertNotNull(last);
        assertEquals("e" + (EventsStore.MAX_HISTORY + 9), last.id());
    }

    @Test
    void closeOpenClosesEverythingLeft(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        store.recordStart(event("e0"), 1_000L, false);
        store.closeOpen(2_000L);
        EventsStore.Entry last = store.last();
        assertNotNull(last);
        assertFalse(last.open());
        assertEquals(1, last.duration());
    }

    @Test
    void unknownIdsCountAsZero(@TempDir Path dir) {
        EventsStore store = new EventsStore(dir, LOGGER);
        assertEquals(0, store.total(null));
        assertEquals(0, store.total(""));
    }
}
