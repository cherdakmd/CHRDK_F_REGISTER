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
    void brokenFileDoesNotCrashLoad() throws Exception {
        Files.writeString(folder.resolve("treasures.json"), "{ это не json ");
        TreasureStore store = store();
        String player = UUID.randomUUID().toString();
        assertEquals(0L, store.mined(player));
        store.addMined(player, 1L);
        assertEquals(1L, store.mined(player));
    }
}
