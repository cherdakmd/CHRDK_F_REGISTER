package ru.cherdakmd.chrdkprofessions;

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
 * Файл прогресса профессий (1.4.165).
 *
 * <p>Хранится только выбранное ремесло, опыт и суточный счётчик; уровень
 * выводится из опыта. Тесты проверяют круг «сохранили — прочитали», потолки и
 * то, что битый файл не уносит данные остальных игроков.</p>
 */
class ProfessionStoreTest {

    private static final Logger LOGGER = Logger.getLogger("ProfessionStoreTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertEquals(dir.resolve("professions.json"), store.file());
        assertNull(store.active(PLAYER), "новый игрок без ремесла");
    }

    @Test
    void selectionAndExperienceRoundTrip(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.rename(PLAYER, "Чердак");
        assertTrue(store.setActive(PLAYER, "smith", NOW));
        assertEquals(Profession.SMITH, store.active(PLAYER));
        assertEquals(NOW, store.changedAt(PLAYER));
        store.addXp(PLAYER, Profession.SMITH, 260);
        store.setDaily(PLAYER, Profession.SMITH, "2026-10-25", 120);
        store.saveNow();
        assertTrue(Files.isRegularFile(store.file()));

        ProfessionStore loaded = new ProfessionStore(dir, LOGGER);
        loaded.load();
        assertEquals("Чердак", loaded.name(PLAYER));
        assertEquals(Profession.SMITH, loaded.active(PLAYER));
        assertEquals(260, loaded.xp(PLAYER, Profession.SMITH));
        assertEquals(260, loaded.totalXp(PLAYER));
        assertEquals("2026-10-25", loaded.dailyDate(PLAYER, Profession.SMITH));
        assertEquals(120, loaded.dailyAmount(PLAYER, Profession.SMITH));
        assertEquals(NOW, loaded.changedAt(PLAYER));
        assertFalse(loaded.isDirty(), "чтение не делает файл грязным");
    }

    @Test
    void experienceIsCappedAndKeptPerProfession(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        assertEquals(0, store.xp(PLAYER, Profession.COOK), "опыт чужой профессии — 0");
        assertEquals(50, store.addXp(PLAYER, Profession.COOK, 50));
        assertEquals(50, store.addXp(PLAYER, Profession.COOK, 0), "ноль не меняет опыт");
        assertEquals(50, store.addXp(PLAYER, Profession.COOK, -10));
        assertEquals(ProfessionStore.MAX_XP, store.addXp(PLAYER, Profession.COOK,
                ProfessionStore.MAX_XP + 500));
        assertEquals(0, store.xp(PLAYER, Profession.SMITH));
        assertEquals(ProfessionStore.MAX_XP, store.totalXp(PLAYER));
    }

    @Test
    void selectionKeepsOtherExperience(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        assertTrue(store.setActive(PLAYER, "smith", NOW));
        store.addXp(PLAYER, Profession.SMITH, 300);
        assertTrue(store.setActive(PLAYER, "mason", NOW + 1_000));
        assertEquals(Profession.MASON, store.active(PLAYER));
        assertEquals(300, store.xp(PLAYER, Profession.SMITH), "опыт прежнего ремесла сохранён");
        assertFalse(store.setActive(PLAYER, "mason", NOW + 2_000), "то же ремесло — не изменение");
        assertFalse(store.setActive(PLAYER, "паладин", NOW), "неизвестное ремесло не пишется");
        assertEquals(Profession.MASON, store.active(PLAYER));
    }

    @Test
    void namesCountsAndResets(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.rename(PLAYER, "Чердак");
        store.rename(OTHER, "Гость");
        store.setActive(PLAYER, "smith", NOW);
        store.setActive(OTHER, "smith", NOW);
        store.addXp(PLAYER, Profession.SMITH, 300);
        store.addXp(OTHER, Profession.SMITH, 260);

        assertEquals(PLAYER, store.byName("чердак"), "поиск без учёта регистра");
        assertEquals(PLAYER, store.byName("ЧЕРДАК"));
        assertNull(store.byName(null));
        assertNull(store.byName(""));
        assertNull(store.byName("никого"));
        assertEquals(2, store.countOf(Profession.SMITH));
        assertEquals(0, store.countOf(Profession.COOK));
        assertEquals(0, store.countOf(null));
        assertEquals(5L, store.levelSum(Profession.SMITH), "2-й и 3-й уровни");
        assertEquals(0L, store.levelSum(Profession.MASON));

        assertTrue(store.clearActive(PLAYER));
        assertNull(store.active(PLAYER));
        assertEquals(300, store.xp(PLAYER, Profession.SMITH), "сброс выбора не трогает опыт");
        assertFalse(store.clearActive(PLAYER), "уже пусто");

        assertTrue(store.reset(OTHER));
        assertEquals(0, store.xp(OTHER, Profession.SMITH));
        assertNull(store.active(OTHER));
        assertFalse(store.reset(OTHER), "сбрасывать больше нечего");
    }

    @Test
    void cleanStoreIsNotWritten(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.of(PLAYER);
        store.saveNow();
        assertFalse(Files.exists(store.file()), "без изменений файл не создаётся");
        store.addXp(PLAYER, Profession.SMITH, 10);
        assertTrue(store.isDirty());
        store.flush();
        assertTrue(Files.isRegularFile(store.file()));
        assertFalse(store.isDirty());
    }

    @Test
    void brokenFileFailsClosed(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("professions.json"), "{[мусор", StandardCharsets.UTF_8);
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,store::load);
        assertFalse(store.healthy());
        store.flush();
        assertEquals("{[мусор",Files.readString(store.file()));
    }

    @Test
    void unknownProfessionInFileIsDropped(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("professions.json"), """
                {
                  "version": 1,
                  "players": {
                    "%s": {
                      "name": "Чердак",
                      "active": "паладин",
                      "xp": {"smith": 260, "паладин": 50},
                      "daily": {"smith": {"date": "2026-10-25", "amount": 120}},
                      "changed-at": %d
                    }
                  }
                }
                """.formatted(PLAYER, NOW), StandardCharsets.UTF_8);
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.size());
        assertNull(store.active(PLAYER), "неизвестное ремесло не выбирается");
        assertEquals(260, store.xp(PLAYER, Profession.SMITH));
        assertEquals(0, store.totalXp(PLAYER) - 260, "опыт чужого ремесла отброшен");
        assertEquals(120, store.dailyAmount(PLAYER, Profession.SMITH));
    }

    @Test
    void nullPlayerIsRejected(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        assertThrows(IllegalArgumentException.class, () -> store.of(null));
        assertThrows(IllegalArgumentException.class, () -> store.xp(null, Profession.SMITH));
    }
}
