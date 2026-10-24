package ru.cherdakmd.chrdkquests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище прогресса (1.4.161): {@code quests.json}, счётчики, суточный лимит
 * и чтение битого файла.
 */
class QuestStoreTest {

    private static final Logger LOGGER = Logger.getLogger("QuestStoreTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.load();
        assertEquals(dir.resolve("quests.json"), store.file());
        assertEquals(0, store.size());
        assertEquals("", store.name(PLAYER));
        assertEquals("", store.faction(PLAYER));
        assertEquals(0, store.reputation(PLAYER, "watch"));
        assertEquals(0, store.progress(PLAYER, "q"));
        assertFalse(store.done(PLAYER, "q"));
        assertEquals(0, store.doneCount(PLAYER));
        assertEquals(0, store.claims(PLAYER, "2026-10-24"));
        assertFalse(store.isDirty());
        assertNull(store.byName("никого"));
        assertNull(store.byName(null));
    }

    @Test
    void mutationsAreTrackedAsDirty(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.rename(PLAYER, "Странник");
        assertTrue(store.isDirty());
        assertEquals("Странник", store.name(PLAYER));
        assertEquals(PLAYER, store.byName("странник"));
        assertEquals(PLAYER, store.byName("Странник"));

        assertTrue(store.setFaction(PLAYER, " WATCH "));
        assertEquals("watch", store.faction(PLAYER));
        assertFalse(store.setFaction(PLAYER, "watch"), "повтор ничего не меняет");

        assertEquals(30, store.addReputation(PLAYER, "watch", 30));
        assertEquals(35, store.addReputation(PLAYER, "watch", 5));
        assertEquals(0, store.addReputation(PLAYER, "watch", -10), "минус не отнимает");
        assertEquals(35, store.reputation(PLAYER, "watch"));

        assertEquals(2, store.addProgress(PLAYER, "q", 2, 5));
        assertEquals(4, store.addProgress(PLAYER, "q", 2, 5));
        assertEquals(5, store.addProgress(PLAYER, "q", 10, 5), "цель не превышается");
        assertEquals(5, store.progress(PLAYER, "q"));
        assertEquals(5, store.addProgress(PLAYER, "q", -1, 5), "минус прогресс не двигает");
    }

    @Test
    void markDoneClearsProgress(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.addProgress(PLAYER, "q", 3, 5);
        store.markDone(PLAYER, "Q");
        assertTrue(store.done(PLAYER, "q"));
        assertEquals(0, store.progress(PLAYER, "q"), "прогресс закрытого задания не нужен");
        assertEquals(1, store.doneCount(PLAYER));
        store.markDone(PLAYER, "q");
        assertEquals(1, store.doneCount(PLAYER), "повторная отметка не задваивает");
        assertEquals(1, store.totalDone());
    }

    @Test
    void dailyClaimsResetOnNewDay(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        assertTrue(store.countClaim(PLAYER, "2026-10-24", 2));
        assertTrue(store.countClaim(PLAYER, "2026-10-24", 2));
        assertFalse(store.countClaim(PLAYER, "2026-10-24", 2), "лимит выбран");
        assertEquals(2, store.claims(PLAYER, "2026-10-24"));
        assertEquals(0, store.claims(PLAYER, "2026-10-25"), "новый день — новый счёт");
        assertTrue(store.countClaim(PLAYER, "2026-10-25", 2));
        assertEquals(1, store.claims(PLAYER, "2026-10-25"));
        assertFalse(store.countClaim(PLAYER, null, 2));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.rename(PLAYER, "Странник");
        store.setFaction(PLAYER, "watch");
        store.addReputation(PLAYER, "watch", 260);
        store.addReputation(PLAYER, "guild", 40);
        store.addProgress(PLAYER, "q", 4, 10);
        store.markDone(PLAYER, "old");
        store.countClaim(PLAYER, "2026-10-24", 10);
        store.saveNow();
        assertFalse(store.isDirty(), "после записи флаг снят");

        QuestStore loaded = new QuestStore(dir, LOGGER);
        loaded.load();
        assertEquals(1, loaded.size());
        assertEquals("Странник", loaded.name(PLAYER));
        assertEquals("watch", loaded.faction(PLAYER));
        assertEquals(260, loaded.reputation(PLAYER, "watch"));
        assertEquals(40, loaded.reputation(PLAYER, "guild"));
        assertEquals(4, loaded.progress(PLAYER, "q"));
        assertTrue(loaded.done(PLAYER, "old"));
        assertEquals(1, loaded.claims(PLAYER, "2026-10-24"));
        assertFalse(loaded.isDirty(), "чужой файл не делает хранилище грязным");
        assertEquals(PLAYER, loaded.byName("странник"));
    }

    @Test
    void saveWithoutChangesDoesNotTouchFile(@TempDir Path dir) throws Exception {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.saveNow();
        assertFalse(Files.exists(store.file()), "грязного состояния не было — файл не создан");
        store.rename(PLAYER, "Странник");
        store.saveNow();
        assertTrue(Files.exists(store.file()));
    }

    @Test
    void brokenFileIsIgnored(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("quests.json"), "{это не json", StandardCharsets.UTF_8);
        QuestStore store = new QuestStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.size());
        store.rename(PLAYER, "Странник");
        assertEquals("Странник", store.name(PLAYER));
    }

    @Test
    void brokenRowsAreSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("quests.json"), """
                {
                  "version": 1,
                  "players": {
                    "не-uuid": {"name": "Кто-то"},
                    "00000000-0000-0000-0000-0000000000aa": "строка",
                    "00000000-0000-0000-0000-0000000000bb": {
                      "name": "Живой",
                      "faction": " WATCH ",
                      "reputation": {"watch": 99999999999, "": 5},
                      "progress": {"q": -100, "q2": 99999999999},
                      "done": ["old", "", null, "OLD2"],
                      "claim-day": "2026-10-24",
                      "claim-count": 999
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        QuestStore store = new QuestStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.size(), "битые записи пропущены");
        assertEquals("Живой", store.name(OTHER));
        assertEquals("watch", store.faction(OTHER));
        assertEquals(QuestFaction.MAX_REPUTATION, store.reputation(OTHER, "watch"),
                "репутация зажата");
        assertEquals(0, store.progress(OTHER, "q"), "отрицательный прогресс зажат в ноль");
        assertEquals(Quest.MAX_AMOUNT, store.progress(OTHER, "q2"));
        assertEquals(2, store.doneCount(OTHER));
        assertEquals(QuestConfig.MAX_CLAIMS_PER_DAY, store.claims(OTHER, "2026-10-24"));
    }

    @Test
    void resetClearsEverything(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.setFaction(PLAYER, "watch");
        store.addReputation(PLAYER, "watch", 100);
        store.addProgress(PLAYER, "q", 1, 5);
        store.markDone(PLAYER, "other");
        store.countClaim(PLAYER, "2026-10-24", 10);
        assertTrue(store.reset(PLAYER));
        assertEquals("", store.faction(PLAYER));
        assertEquals(0, store.reputation(PLAYER, "watch"));
        assertEquals(0, store.progress(PLAYER, "q"));
        assertEquals(0, store.doneCount(PLAYER));
        assertEquals(0, store.claims(PLAYER, "2026-10-24"));
        assertFalse(store.reset(UUID.fromString("00000000-0000-0000-0000-0000000000cc")),
                "неизвестного игрока сбрасывать нечего");
    }

    @Test
    void snapshotHasVersionAndIsWritable(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        store.rename(PLAYER, "Странник");
        String json = store.snapshotJson();
        assertTrue(json.contains("\"version\": " + QuestStore.VERSION));
        assertTrue(json.contains(PLAYER.toString()));
        store.flush();
        assertTrue(Files.isRegularFile(store.file()));
    }

    @Test
    void nullPlayerIsRejected(@TempDir Path dir) {
        QuestStore store = new QuestStore(dir, LOGGER);
        assertThrows(IllegalArgumentException.class, () -> store.of(null));
    }
}
