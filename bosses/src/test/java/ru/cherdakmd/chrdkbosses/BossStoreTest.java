package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Память боссов (1.4.162): {@code bosses.json}, паузы, счётчики и история.
 *
 * <p>Переживают перезапуск только паузы и победы: сам бой — событие в мире.
 * Поэтому хранилище проверяется отдельно от сервиса, без живого сервера.</p>
 */
class BossStoreTest {

    private static final Logger LOGGER = Logger.getLogger("BossStoreTest");
    private static final long NOW = 1_792_000_000_000L;

    private static BossDefinition boss(String id, String name) {
        return new BossDefinition(id, name, "", EntityType.ZOMBIE, 100, Material.PAPER, true,
                new Arena("world", 0, 64, 0, 10),
                BossPhase.normalize(List.of()),
                new BossReward(100, List.of(), Material.AIR, ""),
                1_800, 1_800, 2, true);
    }

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.load();
        assertEquals(dir.resolve("bosses.json"), store.file());
        assertEquals(0L, store.nextAt("ash"));
        assertEquals(0, store.kills("ash"));
        assertEquals(0, store.total());
        assertEquals(0, store.killsAll());
        assertTrue(store.history().isEmpty());
        assertTrue(store.nextAll().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void cooldownsAreTrackedAndCleared(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.setNextAt("Ash", NOW);
        assertEquals(NOW, store.nextAt(" ash "), "ид нормализуется");
        assertTrue(store.isDirty());
        assertEquals(1, store.nextAll().size());

        assertTrue(store.clearNextAt("ash"));
        assertEquals(0L, store.nextAt("ash"));
        assertFalse(store.clearNextAt("ash"), "снимать нечего");

        store.setNextAt("first", NOW);
        store.setNextAt("second", NOW);
        assertEquals(2, store.clearAllNextAt());
        assertEquals(0, store.clearAllNextAt());

        store.setNextAt("", NOW);
        assertEquals(0L, store.nextAt(""), "пустой ид не пишется");
        store.setNextAt("zero", 0);
        assertEquals(0L, store.nextAt("zero"));
    }

    @Test
    void killUpdatesCountersAndHistory(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.recordKill(boss("ash", "Пепел"), "Странник", 3, NOW);
        assertEquals(1, store.kills("ash"));
        assertEquals(1, store.total());
        assertEquals(1, store.killsAll());
        assertEquals(1, store.history().size());

        BossStore.Entry entry = store.history().get(0);
        assertEquals("ash", entry.id());
        assertEquals("Пепел", entry.name());
        assertEquals("Странник", entry.killer());
        assertEquals(3, entry.participants());
        assertEquals(NOW, entry.endedAt());
        assertNotEquals("—", entry.time());

        store.recordKill(boss("frost", "Мороз"), "Кто-то", 1, NOW + 1_000L);
        assertEquals(2, store.total());
        assertEquals(2, store.killsAll());
        assertEquals(1, store.kills("frost"));
    }

    @Test
    void historyIsTrimmed(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        for (int index = 0; index < BossStore.MAX_HISTORY + 10; index++) {
            store.recordKill(boss("ash", "Пепел"), "Боец", 1, NOW + index);
        }
        assertEquals(BossStore.MAX_HISTORY, store.history().size());
        assertEquals(BossStore.MAX_HISTORY + 10, store.total());
        assertEquals(NOW + BossStore.MAX_HISTORY + 9,
                store.history().get(store.history().size() - 1).endedAt(),
                "новые записи остаются, старые уходят");
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.setNextAt("ash", NOW);
        store.recordKill(boss("ash", "Пепел"), "Странник", 4, NOW);
        store.saveNow();
        assertTrue(Files.isRegularFile(store.file()));
        assertFalse(store.isDirty(), "после записи флаг снимается");
        store.saveNow();
        assertFalse(store.isDirty(), "повторная запись без изменений ничего не пишет");

        BossStore loaded = new BossStore(dir, LOGGER);
        loaded.load();
        assertEquals(NOW, loaded.nextAt("ash"));
        assertEquals(1, loaded.kills("ash"));
        assertEquals(1, loaded.total());
        assertEquals(1, loaded.history().size());
        assertEquals("Странник", loaded.history().get(0).killer());
        assertEquals(4, loaded.history().get(0).participants());
        assertFalse(loaded.isDirty());
    }

    @Test
    void flushWritesEvenWithoutChanges(@TempDir Path dir) throws Exception {
        BossStore store = new BossStore(dir, LOGGER);
        store.flush();
        assertTrue(Files.isRegularFile(store.file()));
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""), "в файле есть версия формата");
        assertTrue(json.contains("\"total\""), "и счётчик побед");
    }

    @Test
    void brokenFileDoesNotThrow(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bosses.json"), "{ это не json", StandardCharsets.UTF_8);
        BossStore store = new BossStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.total());
        assertTrue(store.history().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void brokenEntriesAreSkipped(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bosses.json"), """
                {
                  "version": 1,
                  "next": {"ash": 1792000000000, "": 5, "neg": -1},
                  "kills": {"ash": 2, "": 9},
                  "total": 2,
                  "history": [
                    {"id": "ash", "name": "Пепел", "participants": 2, "ended-at": 1792000000000},
                    {"name": "Без ида", "participants": 1},
                    "строка"
                  ]
                }
                """, StandardCharsets.UTF_8);
        BossStore store = new BossStore(dir, LOGGER);
        store.load();
        assertEquals(NOW, store.nextAt("ash"));
        assertEquals(0L, store.nextAt("neg"), "отрицательная пауза не читается");
        assertEquals(2, store.kills("ash"));
        assertEquals(2, store.total());
        assertEquals(1, store.history().size(), "записи без ида и не-объекты пропущены");
        assertEquals("Пепел", store.history().get(0).name());
        assertEquals("", store.history().get(0).killer());
    }

    @Test
    void resetKeepsCooldowns(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.setNextAt("ash", NOW);
        store.recordKill(boss("ash", "Пепел"), "Боец", 1, NOW);
        store.resetCounters();
        assertEquals(0, store.total());
        assertEquals(0, store.kills("ash"));
        assertTrue(store.history().isEmpty());
        assertEquals(NOW, store.nextAt("ash"), "пауза не сбрасывается");
        assertTrue(store.isDirty());
    }

    @Test
    void nullKillIsIgnored(@TempDir Path dir) {
        BossStore store = new BossStore(dir, LOGGER);
        store.recordKill(null, "Никто", 0, NOW);
        assertEquals(0, store.total());
        assertFalse(store.isDirty());
        assertNotNull(store.history());
    }
}
