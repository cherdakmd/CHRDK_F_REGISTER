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
    void rebirthResetsProgressAndAdvancesCounterInOneSnapshot() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 123);
        store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 5);
        store.setBranch(PLAYER, BuildBranch.WARRIOR, 2);
        store.addBonusPoints(PLAYER, 3);
        assertEquals(1, store.applyRebirth(PLAYER));
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(1, restored.rebirths(PLAYER));
        assertEquals(0, restored.xp(PLAYER, SkillType.MINING));
        assertEquals(0, restored.attribute(PLAYER, PlayerAttribute.STRENGTH));
        assertEquals(0, restored.branch(PLAYER, BuildBranch.WARRIOR));
        assertEquals(3, restored.bonusPoints(PLAYER));
    }

    @Test
    void concurrentSavingNeverPersistsHalfARebirth() throws Exception {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addXp(PLAYER, SkillType.MINING, 42);
        store.flush();
        Thread saver = new Thread(() -> {
            for (int i = 0; i < 200; i++) store.flush();
        });
        saver.start();
        store.applyRebirth(PLAYER);
        saver.join();
        store.saveNow();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(1, restored.rebirths(PLAYER));
        assertEquals(0, restored.xp(PLAYER, SkillType.MINING));
    }

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
    void attributesSurviveRestartAndReset() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 12));
        assertTrue(store.addAttribute(PLAYER, PlayerAttribute.STAMINA, 8));
        assertEquals(12, store.attribute(PLAYER, PlayerAttribute.STRENGTH));
        assertEquals(20, store.spentAttributes(PLAYER));
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(12, restored.attribute(PLAYER, PlayerAttribute.STRENGTH),
                "атрибуты переживают перезапуск");
        assertEquals(8, restored.attribute(PLAYER, PlayerAttribute.STAMINA));
        assertEquals(20, restored.spentAttributes(PLAYER));
        restored.resetAttributes(PLAYER);
        assertEquals(0, restored.spentAttributes(PLAYER), "сброс возвращает очки в свободный пул");
        assertFalse(restored.addAttribute(PLAYER, null, 1), "неизвестный атрибут отклоняется");
    }

    @Test
    void buildSnapshotDistinguishesMissingAndIsImmutableAcrossChangesAndRestart() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.buildSnapshot(PLAYER).isEmpty());
        store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 12);
        store.setBranch(PLAYER, BuildBranch.PRIEST, 6);
        SkillStore.BuildSnapshot snapshot = store.buildSnapshot(PLAYER).orElseThrow();
        assertEquals(12, snapshot.attributes().get(PlayerAttribute.STRENGTH));
        assertEquals(0, snapshot.attributes().get(PlayerAttribute.AGILITY));
        assertEquals(6, snapshot.branches().get(BuildBranch.PRIEST));
        assertEquals(0, snapshot.branches().get(BuildBranch.WARRIOR));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.attributes().put(PlayerAttribute.STRENGTH, 99));
        store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 2);
        assertEquals(12, snapshot.attributes().get(PlayerAttribute.STRENGTH));
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        var restoredSnapshot = restored.buildSnapshot(PLAYER).orElseThrow();
        assertEquals(14, restoredSnapshot.attributes().get(PlayerAttribute.STRENGTH));
        assertEquals(6, restoredSnapshot.branches().get(BuildBranch.PRIEST));
        assertTrue(restored.buildSnapshot("unknown").isEmpty());
    }

    @Test
    void branchesBonusAndPveSurviveRestart() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.setBranch(PLAYER, BuildBranch.WARRIOR, 3));
        assertTrue(store.setBranch(PLAYER, BuildBranch.PRIEST, 6));
        assertEquals(3, store.branch(PLAYER, BuildBranch.WARRIOR));
        assertEquals(9, store.spentBranches(PLAYER), "уровни веток тратят очки общего пула");
        store.addBonusPoints(PLAYER, 4);
        store.addPve(PLAYER, 1_250L);
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(6, restored.branch(PLAYER, BuildBranch.PRIEST), "ветки переживают перезапуск");
        assertEquals(4, restored.bonusPoints(PLAYER), "бонусные очки за вклад сохраняются");
        assertEquals(1_250L, restored.pve(PLAYER), "журнал вклада сохраняется");
        restored.resetBranches(PLAYER);
        assertEquals(0, restored.spentBranches(PLAYER), "сброс веток возвращает очки в пул");
        assertFalse(restored.setBranch(PLAYER, BuildBranch.MAGE, BuildBranch.MAX_LEVEL + 1),
                "уровень ветки ограничен потолком");
    }

    @Test
    void pvePointsAreNotCappedPerDayAndPersistAboveOldLoadLimit() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertEquals(12, store.awardPve(PLAYER, 6_000));
        assertEquals(0, store.awardPve(PLAYER, 499));
        assertEquals(1, store.awardPve(PLAYER, 1));
        store.addBonusPoints(PLAYER, 100_000);
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(100_013, restored.bonusPoints(PLAYER));
        assertEquals(6_500, restored.pve(PLAYER));
        assertEquals(1, restored.awardPve(PLAYER, 500));
        assertEquals(100_014, restored.bonusPoints(PLAYER));
    }

    @Test
    void largeAttributeValuesRoundTripAndTotalsDoNotOverflow() {
        SkillStore store = new SkillStore(folder, LOGGER);
        assertTrue(store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, Integer.MAX_VALUE));
        assertFalse(store.addAttribute(PLAYER, PlayerAttribute.STRENGTH, 1));
        assertTrue(store.addAttribute(PLAYER, PlayerAttribute.AGILITY, 200_000));
        assertEquals((long) Integer.MAX_VALUE + 200_000, store.spentAttributesLong(PLAYER));
        assertEquals(Integer.MAX_VALUE, store.spentAttributes(PLAYER));
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(Integer.MAX_VALUE, restored.attribute(PLAYER, PlayerAttribute.STRENGTH));
        assertEquals(200_000, restored.attribute(PLAYER, PlayerAttribute.AGILITY));
        assertEquals((long) Integer.MAX_VALUE + 200_000, restored.spentAttributesLong(PLAYER));
    }

    @Test
    void pveAndPointCountersSaturateWithoutOverflow() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.addBonusPoints(PLAYER, Integer.MAX_VALUE - 1);
        assertEquals(1, store.awardPve(PLAYER, Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, store.bonusPoints(PLAYER));
        assertEquals(Long.MAX_VALUE, store.pve(PLAYER));
        assertEquals(0, store.awardPve(PLAYER, 1));
        store.flush();
        SkillStore restored = new SkillStore(folder, LOGGER);
        restored.load();
        assertEquals(Integer.MAX_VALUE, restored.bonusPoints(PLAYER));
        assertEquals(Long.MAX_VALUE, restored.pve(PLAYER));
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
        assertFalse(store.healthy());
        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
        store.addXp(PLAYER, SkillType.MINING, 7);
        store.saveNow();
        store.flush();
        assertEquals("{ это не json", Files.readString(store.file(), StandardCharsets.UTF_8));
    }

    @Test
    void malformedShapeNeverOverwritesOriginalOrKeepsPartialPlayers() throws Exception {
        String original = "{\"version\":1,\"players\":{\"" + PLAYER
                + "\":{\"xp\":{\"mining\":10}},\"bad\":42}}";
        Files.writeString(folder.resolve("skills.json"), original);
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertFalse(store.healthy());
        assertEquals(0L, store.xp(PLAYER, SkillType.MINING));
        store.flush();
        assertEquals(original, Files.readString(store.file()));
    }

    @Test
    void unsupportedVersionIsNotSilentlyRewritten() throws Exception {
        String original = "{\"version\":2,\"players\":{}}";
        Files.writeString(folder.resolve("skills.json"), original);
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertFalse(store.healthy());
        store.saveNow();
        store.flush();
        assertEquals(original, Files.readString(store.file()));
    }

    @Test
    void invalidRebirthAccountingIsNeverSilentlyTruncated() throws Exception {
        for (String fields : List.of("\"rebirths\":1.5", "\"rebirths\":1000001",
                "\"rebirthConverted\":\"true\"", "\"claimedRebirthMilestones\":[5,2.5]",
                "\"rebirthUniques\":[null]")) {
            String original = "{\"version\":1,\"players\":{\"" + PLAYER + "\":{" + fields + "}}}";
            Files.writeString(folder.resolve("skills.json"), original);
            SkillStore store = new SkillStore(folder, LOGGER);
            store.load();
            assertFalse(store.healthy(), fields);
            store.flush();
            assertEquals(original, Files.readString(store.file()));
        }
    }

    @Test
    void missingFileIsValidForNewServer() {
        SkillStore store = new SkillStore(folder, LOGGER);
        store.load();
        assertTrue(store.healthy());
        store.addXp(PLAYER, SkillType.MINING, 7);
        store.saveNow();
        assertTrue(Files.isRegularFile(store.file()));
    }
}
