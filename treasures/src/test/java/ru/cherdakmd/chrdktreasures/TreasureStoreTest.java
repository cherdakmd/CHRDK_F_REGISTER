package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureStoreTest {

    private static final Logger LOGGER = Logger.getLogger("TreasureStoreTest");

    @TempDir
    Path folder;

    private TreasureStore store() {
        TreasureStore store = new TreasureStore(folder, LOGGER);
        store.load();
        return store;
    }

    @Test
    void progressCountsMinedTreasuresAndCollection() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        assertEquals(0L, store.mined(player));
        assertEquals(0L, store.found(player));
        assertEquals(0, store.foundItems(player));

        store.addMined(player, 5L);
        assertEquals(5L, store.mined(player));
        store.recordTreasure(player, TreasureTier.COMMON, "plains-reap-hook");
        store.recordTreasure(player, TreasureTier.COMMON, "plains-reap-hook");
        store.recordTreasure(player, TreasureTier.RARE, null);

        assertEquals(3L, store.found(player));
        assertEquals(2L, store.tierCount(player, TreasureTier.COMMON));
        assertEquals(1L, store.tierCount(player, TreasureTier.RARE));
        assertEquals(0L, store.tierCount(player, TreasureTier.EPIC));
        assertEquals(2, store.itemCount(player, "plains-reap-hook"));
        assertEquals(1, store.foundItems(player));
        assertEquals(Set.of("plains-reap-hook"), store.itemIds(player));
        assertTrue(store.isDirty());
    }

    @Test
    void invalidWritesAreIgnored() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        store.addMined(player, 0L);
        store.addMined(player, -5L);
        store.recordTreasure(player, null, "x");
        store.recordTreasure(null, TreasureTier.COMMON, null);
        store.setName(player, "   ");
        assertEquals(0L, store.mined(player));
        assertEquals(0L, store.found(player));
        assertEquals("", store.name(player));
        assertFalse(store.isDirty(), "пустые операции не должны помечать данные грязными");
    }

    @Test
    void saveAndReloadRestoresEverything() throws Exception {
        String player = UUID.randomUUID().toString();
        TreasureStore store = store();
        store.setName(player, "Стив");
        store.addMined(player, 12L);
        store.recordTreasure(player, TreasureTier.EPIC, "desert-pharaoh-blade");
        store.setSounds(player, false);
        store.setParticles(player, false);
        store.setAnnounce(player, false);
        assertTrue(store.markMilestone(player, 10));
        store.saveNow();

        Path file = folder.resolve("treasures.json");
        assertTrue(Files.isRegularFile(file));
        String json = Files.readString(file);
        assertTrue(json.contains("\"mined\": 12"), "нет счётчика добычи: " + json);
        assertTrue(json.contains("\"desert-pharaoh-blade\""), "нет коллекции");

        TreasureStore reloaded = store();
        assertEquals(12L, reloaded.mined(player));
        assertEquals("Стив", reloaded.name(player));
        assertEquals(1L, reloaded.tierCount(player, TreasureTier.EPIC));
        assertEquals(1, reloaded.itemCount(player, "desert-pharaoh-blade"));
        assertFalse(reloaded.sounds(player));
        assertFalse(reloaded.particles(player));
        assertFalse(reloaded.announce(player));
        assertEquals(Set.of(10), reloaded.milestones(player));
        assertFalse(reloaded.isDirty(), "после загрузки данных нет изменений");
    }

    @Test
    void shardsAccumulateSpendAndSurviveReload() throws Exception {
        String player = UUID.randomUUID().toString();
        TreasureStore store = store();
        assertEquals(0L, store.shards(player));
        store.addShards(player, 20);
        store.addShards(player, 5);
        store.addShards(player, 0);
        store.addShards(player, -3);
        assertEquals(25L, store.shards(player));
        assertEquals(0L, store.shardsSpent(player));

        assertFalse(store.takeShards(player, 30), "нельзя уйти в минус");
        assertEquals(25L, store.shards(player));
        assertTrue(store.takeShards(player, 25));
        assertEquals(0L, store.shards(player));
        assertEquals(25L, store.shardsSpent(player));
        assertFalse(store.takeShards(player, 1), "пустой баланс не оплачивает покупку");
        assertTrue(store.isDirty());

        store.addShards(player, 7);
        store.saveNow();
        TreasureStore reloaded = store();
        assertEquals(7L, reloaded.shards(player));
        assertEquals(25L, reloaded.shardsSpent(player));
    }

    @Test
    void shardsResetWithProgressButNotWithoutProfile() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        store.addShards(player, 40);
        assertTrue(store.takeShards(player, 15));
        store.reset(player);
        assertEquals(0L, store.shards(player));
        assertEquals(0L, store.shardsSpent(player));
        assertEquals(0L, store.shards(UUID.randomUUID().toString()),
                "чужой профиль не создаётся чтением");
    }

    @Test
    void rankTagChoiceSurvivesReloadAndResetKeepsIt() throws Exception {
        String player = UUID.randomUUID().toString();
        TreasureStore store = store();
        assertEquals(TreasureRankTags.Choice.AUTO, store.rankTag(player),
                "без выбора — как настроено на сервере");
        store.setRankTag(player, TreasureRankTags.Choice.OFF);
        store.addMined(player, 5L);
        store.saveNow();

        TreasureStore reloaded = store();
        assertEquals(TreasureRankTags.Choice.OFF, reloaded.rankTag(player));
        reloaded.reset(player);
        assertEquals(TreasureRankTags.Choice.OFF, reloaded.rankTag(player),
                "сброс прогресса не должен включать звание обратно");
        reloaded.setRankTag(player, null);
        assertEquals(TreasureRankTags.Choice.AUTO, reloaded.rankTag(player));
    }

    @Test
    void milestoneIsGrantedOnce() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        assertTrue(store.markMilestone(player, 10));
        assertFalse(store.markMilestone(player, 10));
        assertTrue(store.markMilestone(player, 25));
        assertEquals(Set.of(10, 25), store.milestones(player));
    }

    @Test
    void topIsSortedByFoundDescending() {
        TreasureStore store = store();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String third = UUID.randomUUID().toString();
        store.recordTreasure(first, TreasureTier.COMMON, null);
        store.recordTreasure(second, TreasureTier.COMMON, null);
        store.recordTreasure(second, TreasureTier.COMMON, null);
        store.recordTreasure(third, TreasureTier.COMMON, null);
        store.recordTreasure(third, TreasureTier.COMMON, null);
        store.recordTreasure(third, TreasureTier.COMMON, null);

        List<Map.Entry<String, Long>> top = store.topByFound(2);
        assertEquals(2, top.size());
        assertEquals(third, top.get(0).getKey());
        assertEquals(3L, top.get(0).getValue());
        assertEquals(second, top.get(1).getKey());
        assertTrue(store.topByFound(0).isEmpty());
        assertEquals(3, store.topByFound(100).size());
    }

    @Test
    void resetClearsProgressButKeepsPreferences() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        store.addMined(player, 40L);
        store.recordTreasure(player, TreasureTier.LEGENDARY, "snow-frost-blade");
        store.markMilestone(player, 10);
        store.setName(player, "Стив");
        store.setSounds(player, false);

        store.reset(player);
        assertEquals(0L, store.mined(player));
        assertEquals(0L, store.found(player));
        assertEquals(0, store.foundItems(player));
        assertTrue(store.milestones(player).isEmpty());
        assertEquals("Стив", store.name(player));
        assertFalse(store.sounds(player), "настройки оформления не сбрасываются");
        assertTrue(store.isDirty());
    }

    @Test
    void flushWritesFileEvenWithoutChanges() {
        TreasureStore store = store();
        store.flush();
        assertTrue(Files.isRegularFile(folder.resolve("treasures.json")));
    }


    @Test
    void streakExtendsWithinWindowAndResetsAfterPause() {
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        long start = 1_000_000L;
        assertEquals(1, store.recordTreasure(player, TreasureTier.COMMON, null,
                start, 10_000L));
        assertEquals(2, store.recordTreasure(player, TreasureTier.RARE, null,
                start + 5_000L, 10_000L));
        assertEquals(3, store.recordTreasure(player, TreasureTier.EPIC, null,
                start + 15_000L, 10_000L));
        assertEquals(1, store.recordTreasure(player, TreasureTier.COMMON, null,
                start + 40_000L, 10_000L));
        assertEquals(1, store.streak(player));
        assertEquals(3, store.bestStreak(player));
        assertEquals(start + 40_000L, store.lastFind(player));
    }

    @Test
    void streakSurvivesReloadAndResetClearsIt() throws Exception {
        String player = UUID.randomUUID().toString();
        TreasureStore store = store();
        long now = 1_700_000_000_000L;
        store.recordTreasure(player, TreasureTier.COMMON, null, now - 1_000L, 600_000L);
        assertEquals(2, store.recordTreasure(player, TreasureTier.COMMON, null,
                now, 600_000L));
        store.saveNow();

        TreasureStore reloaded = store();
        assertEquals(2, reloaded.streak(player));
        assertEquals(2, reloaded.bestStreak(player));
        assertEquals(now, reloaded.lastFind(player));

        reloaded.reset(player);
        assertEquals(0, reloaded.streak(player));
        assertEquals(0, reloaded.bestStreak(player));
        assertEquals(0L, reloaded.lastFind(player));
    }

    @Test
    void resetAllClearsEveryProfile() {
        TreasureStore store = store();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        store.recordTreasure(first, TreasureTier.COMMON, "plains-reap-hook");
        store.recordTreasure(second, TreasureTier.RARE, null);
        assertEquals(2, store.resetAll());
        assertEquals(0L, store.found(first));
        assertEquals(0L, store.found(second));
        assertTrue(store.isDirty());
        assertEquals(0, store.resetAll());
    }

    @Test
    void brokenFileQuarantinesWithoutDestructiveShutdown() throws Exception {
        String original = "{ это не json ";
        Files.writeString(folder.resolve("treasures.json"), original);
        TreasureStore store = new TreasureStore(folder, LOGGER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, store::load);
        assertFalse(store.healthy());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> store.addMined(UUID.randomUUID().toString(), 1L));
        store.flush();
        assertEquals(original, Files.readString(folder.resolve("treasures.json")));
    }
}
