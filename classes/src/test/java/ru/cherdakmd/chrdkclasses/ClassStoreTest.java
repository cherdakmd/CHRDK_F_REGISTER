package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище классов (1.4.164): {@code classes.json}, таланты и битые записи.
 *
 * <p>В файле только выбор игрока. Этот тест — про устойчивость: чужой класс в
 * талантах, неизвестный класс и битый JSON не должны ломать загрузку, иначе
 * один испорченный ключ стоил бы игроку всего дерева.</p>
 */
class ClassStoreTest {

    private static final Logger LOGGER = Logger.getLogger("ClassStoreTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.load();
        assertEquals(dir.resolve("classes.json"), store.file());
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertEquals("", store.classOf(PLAYER));
        assertFalse(store.hasClass(PLAYER));
        assertEquals(0, store.talentCount(PLAYER));
        assertNull(store.byName("никого"));
        assertEquals(0, store.countOf("warrior"));
        assertEquals(0, store.countOf("неведомый"));
    }

    @Test
    void classSelectionIsRecordedOnce(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        assertTrue(store.setClass(PLAYER, "WARRIOR", NOW));
        assertEquals("warrior", store.classOf(PLAYER));
        assertTrue(store.hasClass(PLAYER));
        assertEquals(NOW, store.changedAt(PLAYER));
        assertFalse(store.setClass(PLAYER, "warrior", NOW + 1), "повторный выбор — не изменение");
        assertFalse(store.setClass(PLAYER, "паладин", NOW + 1), "неизвестный класс не пишется");
        assertFalse(store.setClass(PLAYER, null, NOW + 1));
        assertTrue(store.isDirty());
    }

    @Test
    void talentsAreUniqueAndCapped(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "warrior", NOW);
        assertTrue(store.learnTalent(PLAYER, "warrior-stoyka-1"));
        assertFalse(store.learnTalent(PLAYER, "warrior-stoyka-1"), "дубликат не добавляется");
        assertFalse(store.learnTalent(PLAYER, "нет-такого"));
        assertFalse(store.learnTalent(PLAYER, ""));
        assertTrue(store.hasTalent(PLAYER, "warrior-stoyka-1"));
        assertFalse(store.hasTalent(PLAYER, "warrior-natisk-1"));

        int added = 1;
        for (Talent talent : ClassCatalog.talents("warrior")) {
            if (store.learnTalent(PLAYER, talent.id())) {
                added++;
            }
        }
        assertEquals(ClassCatalog.TALENTS_PER_CLASS, added, "все 12 талантов одного класса");
        assertEquals(ClassCatalog.TALENTS_PER_CLASS, store.talentCount(PLAYER));
        assertFalse(store.learnTalent(PLAYER, "mage-chary-1"), "тринадцатый талант не влезает");
    }

    @Test
    void clearTalentsReturnsCountAndMoment(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "archer", NOW);
        store.learnTalent(PLAYER, "archer-metkost-1");
        store.learnTalent(PLAYER, "archer-skorost-1");
        assertEquals(2, store.clearTalents(PLAYER, NOW + 5_000));
        assertEquals(0, store.talentCount(PLAYER));
        assertEquals(NOW + 5_000, store.respecAt(PLAYER));
        assertEquals("archer", store.classOf(PLAYER), "пересборка класс не трогает");
        assertEquals(0, store.clearTalents(PLAYER, NOW + 6_000), "второй раз возвращать нечего");
    }

    @Test
    void clearClassAndResetDiffer(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "cleric", NOW);
        store.learnTalent(PLAYER, "cleric-milost-1");
        store.clearTalents(PLAYER, NOW + 1_000);

        assertTrue(store.clearClass(PLAYER));
        assertEquals("", store.classOf(PLAYER));
        assertEquals(0, store.talentCount(PLAYER));
        assertEquals(0L, store.changedAt(PLAYER));
        assertFalse(store.clearClass(PLAYER), "второй раз стирать нечего");

        store.setClass(PLAYER, "mage", NOW);
        assertTrue(store.reset(PLAYER));
        assertEquals("", store.classOf(PLAYER));
        assertEquals(0L, store.respecAt(PLAYER));
        assertFalse(store.reset(OTHER), "игрока нет в файле — сбрасывать нечего");
    }

    @Test
    void nameAndCountsWork(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "warrior", NOW);
        store.setClass(OTHER, "warrior", NOW);
        store.rename(PLAYER, "Чердак");
        store.rename(OTHER, "Гость");
        assertEquals("Чердак", store.name(PLAYER));
        assertEquals(PLAYER, store.byName("чердак"));
        assertNull(store.byName(""));
        assertEquals(2, store.countOf("warrior"));
        assertEquals(2, store.size());
    }

    @Test
    void abilityMomentSurvivesRestartAndReset(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "warrior", NOW);
        assertEquals(0L, store.abilityAt(PLAYER), "до первого применения — ноль");
        store.setAbilityAt(PLAYER, NOW + 5_000L);
        assertEquals(NOW + 5_000L, store.abilityAt(PLAYER));
        assertTrue(store.isDirty(), "применение умения должно попасть в файл");
        store.setAbilityAt(PLAYER, -5L);
        assertEquals(0L, store.abilityAt(PLAYER), "отрицательный момент не пишется");

        // Сброс класса не должен возвращать умение: иначе /classadmin reset
        // становится бесплатным сбросом кулдауна.
        store.setAbilityAt(PLAYER, NOW);
        store.clearClass(PLAYER);
        assertEquals(NOW, store.abilityAt(PLAYER));
        store.setClass(PLAYER, "archer", NOW);
        store.setAbilityAt(PLAYER, NOW + 1L);
        store.clearTalents(PLAYER, NOW + 2L);
        assertEquals(NOW + 1L, store.abilityAt(PLAYER));

        store.saveNow();
        ClassStore reloaded = new ClassStore(dir, LOGGER);
        reloaded.load();
        assertEquals(NOW + 1L, reloaded.abilityAt(PLAYER), "кулдаун переживает перезапуск");
        assertEquals(0L, reloaded.abilityAt(OTHER), "чужой игрок не задет");
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.rename(PLAYER, "Чердак");
        store.setClass(PLAYER, "archer", NOW);
        store.learnTalent(PLAYER, "archer-metkost-1");
        store.learnTalent(PLAYER, "archer-skorost-1");
        store.clearTalents(PLAYER, NOW + 2_000);
        store.learnTalent(PLAYER, "archer-vyzhivanie-1");
        store.saveNow();
        assertFalse(store.isDirty());

        ClassStore loaded = new ClassStore(dir, LOGGER);
        loaded.load();
        assertEquals("archer", loaded.classOf(PLAYER));
        assertEquals(NOW, loaded.changedAt(PLAYER));
        assertEquals(NOW + 2_000, loaded.respecAt(PLAYER));
        assertEquals(1, loaded.talentCount(PLAYER));
        assertTrue(loaded.hasTalent(PLAYER, "archer-vyzhivanie-1"));
        assertEquals("Чердак", loaded.name(PLAYER));
    }

    @Test
    void brokenFileStartsClean(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("classes.json"), "{[\"мусор", StandardCharsets.UTF_8);
        ClassStore store = new ClassStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertEquals("", store.classOf(PLAYER));
    }

    @Test
    void loadDropsUnknownClassAndForeignTalents(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("classes.json"), """
                {
                  "version": 1,
                  "players": {
                    "%s": {"name": "Чердак", "class": "WARRIOR",
                           "talents": ["warrior-stoyka-1", "mage-chary-1", "нет-такого", ""],
                           "changed-at": %d, "respec-at": -5},
                    "%s": {"name": "Гость", "class": "паладин", "talents": ["mage-chary-1"]},
                    "не-uuid": {"name": "Битый"}
                  }
                }
                """.formatted(PLAYER, NOW, OTHER), StandardCharsets.UTF_8);
        ClassStore store = new ClassStore(dir, LOGGER);
        store.load();
        assertEquals(2, store.size(), "битый ключ пропущен");
        assertEquals("warrior", store.classOf(PLAYER));
        assertEquals(1, store.talentCount(PLAYER), "чужой и неизвестный таланты отброшены");
        assertTrue(store.hasTalent(PLAYER, "warrior-stoyka-1"));
        assertEquals(0L, store.respecAt(PLAYER), "отрицательное время зажато в ноль");
        assertEquals("", store.classOf(OTHER), "неизвестный класс снят");
        assertEquals(0, store.talentCount(OTHER), "без класса талантов нет");
    }

    @Test
    void flushWritesVersionAndPlayers(@TempDir Path dir) throws Exception {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.setClass(PLAYER, "mage", NOW);
        store.flush();
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"players\""));
        assertTrue(json.contains("mage"));
    }

    @Test
    void saveNowSkipsCleanStore(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, LOGGER);
        store.saveNow();
        assertFalse(Files.exists(store.file()), "чистое хранилище не создаёт файл");
        store.of(PLAYER);
        store.saveNow();
        assertFalse(Files.exists(store.file()), "одно обращение без изменений — тоже не запись");
        assertNotNull(store.of(PLAYER));
    }
}
