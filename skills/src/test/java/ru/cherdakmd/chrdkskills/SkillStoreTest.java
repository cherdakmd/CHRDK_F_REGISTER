package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище прогресса: накопление, вехи, ник, топ и запись/чтение файла.
 *
 * <p>Проверяется главное свойство формата: опыт переживает перезапуск, а
 * веха выдаётся ровно один раз — иначе после каждого входа игрок получал бы
 * алмазы за уже пройденные уровни.</p>
 */
class SkillStoreTest {

    private static final Logger LOGGER = Logger.getLogger("chrdk-skills-test");
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path folder;

    @Test
    void addXpAccumulates() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
        assertEquals(5L, store.addXp(PLAYER, SkillType.MINING, 5));
        assertEquals(12L, store.addXp(PLAYER, SkillType.MINING, 7));
        assertEquals(12L, store.xp(PLAYER, SkillType.MINING));
        assertEquals(0L, store.xp(PLAYER, SkillType.FISHING), "другие навыки не растут");
        assertTrue(store.isDirty());
    }

    @Test
    void negativeAddIsIgnored() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 10);
        assertEquals(10L, store.addXp(PLAYER, SkillType.MINING, -5));
        assertEquals(10L, store.xp(PLAYER, SkillType.MINING));
    }

    @Test
    void setXpNeverGoesBelowZero() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.setXp(PLAYER, SkillType.MINING, 100);
        store.setXp(PLAYER, SkillType.MINING, -5);
        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
    }

    @Test
    void milestoneIsGrantedOnce() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.markMilestone(PLAYER, 10));
        assertFalse(store.markMilestone(PLAYER, 10), "повторно веха не выдаётся");
        assertTrue(store.markMilestone(PLAYER, 25));
        assertEquals(2, store.milestones(PLAYER).size());
    }

    @Test
    void allXpCoversEverySkill() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.FISHING, 40);
        Map<SkillType, Long> all = store.allXp(PLAYER);
        assertEquals(SkillType.values().length, all.size());
        assertEquals(40L, all.get(SkillType.FISHING));
        assertEquals(0L, all.get(SkillType.COMBAT));
    }

    @Test
    void nameAndTopAreOrdered() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.setName(PLAYER, "Cherdak");
        store.setName("second", "Alice");
        store.addXp("second", SkillType.FISHING, 500);
        store.addXp(PLAYER, SkillType.MINING, 100);
        assertEquals("Cherdak", store.name(PLAYER));
        assertEquals("", store.name("unknown"));

        List<Map.Entry<String, Long>> top = store.topByTotalXp(10);
        assertEquals(2, top.size());
        assertEquals("second", top.get(0).getKey());
        assertEquals(500L, top.get(0).getValue());
        assertEquals(1, store.topByTotalXp(1).size());
        assertEquals(0, store.topByTotalXp(0).size());
    }

    @Test
    void saveAndLoadRoundTrip() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 1_500);
        store.addXp(PLAYER, SkillType.ENCHANTING, 42);
        store.markMilestone(PLAYER, 10);
        store.setName(PLAYER, "Cherdak");
        store.saveNow();
        assertFalse(store.isDirty(), "после записи флаг снимается");
        assertTrue(Files.isRegularFile(store.file()));

        SkillStore reloaded = new SkillStore(folder, LOGGER);
        reloaded.load();
        assertEquals(1_500L, reloaded.xp(PLAYER, SkillType.MINING));
        assertEquals(42L, reloaded.xp(PLAYER, SkillType.ENCHANTING));
        assertEquals("Cherdak", reloaded.name(PLAYER));
        assertTrue(reloaded.milestones(PLAYER).contains(10));
        assertFalse(reloaded.isDirty(), "чтение не делает хранилище грязным");
    }

    @Test
    void flushWritesEvenWithoutChanges() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.flush();
        assertTrue(Files.isRegularFile(store.file()));
        SkillStore empty = new SkillStore(folder, LOGGER);
        empty.load();
        assertEquals(0L, empty.xp(PLAYER, SkillType.MINING));
    }

    @Test
    void snapshotHasVersionAndPlayers() throws Exception {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 7);
        String json = store.snapshotJson();
        assertTrue(json.contains("\"version\": 1"));
        assertTrue(json.contains("\"players\""));
        assertTrue(json.contains(PLAYER));
        assertTrue(json.contains("\"mining\": 7"));
        assertFalse(store.isDirty());

        store.flush();
        String written = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"version\": 1"));
    }

    @Test
    void brokenFileDoesNotCrashLoad() throws Exception {
        SkillStore store = new SkillStore(folder, LOGGER);
        Files.createDirectories(folder);
        Files.writeString(store.file(), "{ это не json", StandardCharsets.UTF_8);
        store.load();
        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
    }
}
