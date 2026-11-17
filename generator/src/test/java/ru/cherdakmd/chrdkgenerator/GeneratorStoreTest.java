package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * Хранилище генератора: бинд ядра, тиры и модули, бункер, перерождение,
 * карантин повреждённого файла без перезаписи (урок milestone-awards 2.0.13).
 */
class GeneratorStoreTest {

    private static GeneratorStore newStore(Path dir) {
        return new GeneratorStore(dir, Logger.getAnonymousLogger());
    }

    private static GeneratorStore ready(Path dir) throws IOException {
        GeneratorStore store = newStore(dir);
        store.load();
        store.markRegistered();
        return store;
    }

    @Test
    void installBindAndPersist_roundTrip(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        UUID core = UUID.randomUUID();
        assertTrue(store.install(owner, core, "world", 10, 64, -3, 1_000L));
        store.setTier(owner, 3);
        store.setModuleLevel(owner, "luck", 2);
        assertEquals(450L, store.addBunker(owner, 450L, 1_500L));
        store.saveNow();

        GeneratorStore reloaded = newStore(dir);
        reloaded.load();
        assertTrue(reloaded.healthy());
        GeneratorStore.Entry entry = reloaded.entry(owner);
        assertTrue(entry.installed());
        assertEquals(core, entry.coreItem());
        assertEquals("world", entry.world());
        assertEquals(10, entry.x());
        assertEquals(64, entry.y());
        assertEquals(-3, entry.z());
        assertEquals(3, entry.tier());
        assertEquals(2, entry.modules().get("luck").intValue());
        assertEquals(450L, entry.bunker());
        assertEquals(1_500L, entry.lastCalcMillis());
    }

    @Test
    void removeHandsOverBunkerAndClearsBindButKeepsTier(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        UUID core = UUID.randomUUID();
        store.install(owner, core, "world", 1, 2, 3, 100L);
        store.setTier(owner, 5);
        store.addBunker(owner, 120L, 200L);
        assertEquals(120L, store.remove(owner, 300L));
        GeneratorStore.Entry entry = store.entry(owner);
        assertFalse(entry.installed());
        assertNull(entry.coreItem());
        assertNull(entry.world());
        assertEquals(0L, entry.bunker());
        assertEquals(5, entry.tier(), "снятие не сбрасывает тир — сбрасывает только перерождение");
        assertEquals(-1L, store.remove(owner, 300L), "повторно снимать нечего");
    }

    @Test
    void rebirthResetsTierModulesAndBunkerKeepsCoreBind(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        UUID core = UUID.randomUUID();
        store.install(owner, core, "world_nether", 0, 70, 0, 10L);
        store.setTier(owner, 6);
        store.setModuleLevel(owner, "speed", 5);
        store.setModuleLevel(owner, "chunk", 1);
        store.addBunker(owner, 700L, 20L);
        assertEquals(700L, store.rebirth(owner), "бункер выдаётся при перерождении");
        GeneratorStore.Entry entry = store.entry(owner);
        assertTrue(entry.installed());
        assertEquals(core, entry.coreItem());
        assertEquals(1, entry.tier());
        assertTrue(entry.modules().isEmpty());
        assertEquals(0L, entry.bunker());
    }

    @Test
    void installTwiceFailsUntilRemoved(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        assertTrue(store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L));
        assertFalse(store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L),
                "второе ядро без снятия первого — отказ");
        store.remove(owner, 0L);
        assertTrue(store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L));
    }

    @Test
    void moduleLevelAndTierBounds(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L);
        assertThrows(IllegalArgumentException.class, () -> store.setTier(owner, 0));
        assertThrows(IllegalArgumentException.class, () -> store.setTier(owner, 8));
        store.setTier(owner, GeneratorStore.MAX_TIER);
        store.setModuleLevel(owner, "speed", 5);
        assertThrows(IllegalArgumentException.class, () -> store.setModuleLevel(owner, "speed", 6));
        store.setModuleLevel(owner, "chunk", 1);
        assertThrows(IllegalArgumentException.class, () -> store.setModuleLevel(owner, "chunk", 2));
        assertThrows(IllegalArgumentException.class, () -> store.setModuleLevel(owner, "durability", 1));
        assertThrows(IllegalArgumentException.class, () -> GeneratorStore.maxLevel("durability"));
    }

    @Test
    void withdrawBunkerClampsAndSaturates(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L);
        store.addBunker(owner, 50L, 0L);
        assertEquals(0L, store.withdrawBunker(owner, 0L));
        assertEquals(50L, store.withdrawBunker(owner, 80L));
        assertEquals(0L, store.entry(owner).bunker());
        assertEquals(0L, store.withdrawBunker(owner, 80L));
        assertEquals(Long.MAX_VALUE, store.addBunker(owner, Long.MAX_VALUE, 0L));
        assertEquals(Long.MAX_VALUE, store.addBunker(owner, 5L, 0L), "переполнение зажато, не отрицательно");
    }

    @Test
    void lineageStableAcrossReload(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID lineage = store.lineage();
        store.saveNow();
        GeneratorStore reloaded = newStore(dir);
        reloaded.load();
        assertEquals(lineage, reloaded.lineage());
        assertTrue(reloaded.registered());
    }

    @Test
    void duplicateCoreItemRejectedOnLoad(@TempDir Path dir) throws Exception {
        UUID core = UUID.randomUUID();
        String json = """
                {
                  "schema": 1,
                  "lineage": "%s",
                  "registered": true,
                  "players": {
                    "%s": {"coreItem": "%s", "world": "world", "x": 0, "y": 0, "z": 0, "tier": 1, "modules": {}, "bunker": 0, "lastCalcMillis": 0},
                    "%s": {"coreItem": "%s", "world": "world", "x": 5, "y": 0, "z": 5, "tier": 1, "modules": {}, "bunker": 0, "lastCalcMillis": 0}
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), core, UUID.randomUUID(), core);
        Files.writeString(dir.resolve("generator.json"), json, StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "одно ядро на двух владельцев — дюп, файл в карантине");
        String before = Files.readString(dir.resolve("generator.json"));
        store.saveNow();
        assertEquals(before, Files.readString(dir.resolve("generator.json")),
                "карантин не перезаписывает файл");
    }

    @Test
    void corruptedFileQuarantinedAndNotOverwritten(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("generator.json"), "{не json", StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy());
        assertThrows(IllegalStateException.class,
                () -> store.install(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 0, 0, 0L));
        store.saveNow();
        assertEquals("{не json", Files.readString(dir.resolve("generator.json")));
    }

    @Test
    void strictReaderRejectsDuplicateFields(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "schema": 1,
                  "schema": 1,
                  "lineage": "%s",
                  "registered": true,
                  "players": {}
                }
                """.formatted(UUID.randomUUID());
        Files.writeString(dir.resolve("generator.json"), json, StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "дублирующееся поле — мусор, а не состояние");
    }

    @Test
    void fractionalNumbersRejected(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "schema": 1,
                  "lineage": "%s",
                  "registered": true,
                  "players": {
                    "%s": {"coreItem": "%s", "world": "world", "x": 0, "y": 0, "z": 0, "tier": 1, "modules": {}, "bunker": 1.5, "lastCalcMillis": 0}
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Files.writeString(dir.resolve("generator.json"), json, StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "дробь в числах состояния недопустима");
    }

    @Test
    void unregisteredPlayersRejected(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "schema": 1,
                  "lineage": "%s",
                  "registered": false,
                  "players": {
                    "%s": {"coreItem": "%s", "world": "world", "x": 0, "y": 0, "z": 0, "tier": 1, "modules": {}, "bunker": 0, "lastCalcMillis": 0}
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Files.writeString(dir.resolve("generator.json"), json, StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "игроки без регистрации источника — чужой или откатившийся файл");
    }

    @Test
    void detachedRowWithBunkerRejected(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "schema": 1,
                  "lineage": "%s",
                  "registered": true,
                  "players": {
                    "%s": {"x": 0, "y": 0, "z": 0, "tier": 2, "modules": {}, "bunker": 99, "lastCalcMillis": 0}
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        Files.writeString(dir.resolve("generator.json"), json, StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "снятый генератор не хранит бункер — бункер выдаётся при снятии");
    }

    @Test
    void removedTierSurvivesReload(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L);
        store.setTier(owner, 4);
        store.setModuleLevel(owner, "capacity", 3);
        store.remove(owner, 0L);
        store.saveNow();

        GeneratorStore reloaded = newStore(dir);
        reloaded.load();
        assertTrue(reloaded.healthy());
        GeneratorStore.Entry entry = reloaded.entry(owner);
        assertFalse(entry.installed());
        assertEquals(4, entry.tier());
        assertEquals(3, entry.modules().get("capacity").intValue());
    }

    @Test
    void rebirthsAndFuelUsedMsSurviveReload(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L);
        store.rebirth(owner);
        store.rebirth(owner);
        store.setFuelUsedMs(owner, 123_456L);
        store.saveNow();

        GeneratorStore reloaded = newStore(dir);
        reloaded.load();
        assertTrue(reloaded.healthy());
        GeneratorStore.Entry entry = reloaded.entry(owner);
        assertEquals(2, entry.rebirths(), "перерождения считаются");
        assertEquals(123_456L, entry.fuelUsedMs(), "топливный счётчик сохраняется");
        assertThrows(IllegalArgumentException.class, () -> reloaded.setFuelUsedMs(owner, -1L));
    }

    @Test
    void legacyRowWithoutNewCountersLoadsAsZero(@TempDir Path dir) throws Exception {
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve("generator.json"), "{\n"
                + "  \"schema\": 1,\n"
                + "  \"lineage\": \"" + UUID.randomUUID() + "\",\n"
                + "  \"registered\": true,\n"
                + "  \"players\": {\n"
                + "    \"" + owner + "\": {\n"
                + "      \"coreItem\": \"" + UUID.randomUUID() + "\",\n"
                + "      \"world\": \"world\", \"x\": 1, \"y\": 64, \"z\": -1,\n"
                + "      \"tier\": 3,\n"
                + "      \"modules\": {\"speed\": 2},\n"
                + "      \"bunker\": 55,\n"
                + "      \"lastCalcMillis\": 1000\n"
                + "    }\n"
                + "  }\n"
                + "}", StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertTrue(store.healthy(), "старые файлы без счётчиков читаются");
        GeneratorStore.Entry entry = store.entry(owner);
        assertEquals(0, entry.rebirths());
        assertEquals(0L, entry.fuelUsedMs());
    }

    @Test
    void negativeCountersQuarantineTheFile(@TempDir Path dir) throws Exception {
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve("generator.json"), "{\n"
                + "  \"schema\": 1,\n"
                + "  \"lineage\": \"" + UUID.randomUUID() + "\",\n"
                + "  \"registered\": true,\n"
                + "  \"players\": {\n"
                + "    \"" + owner + "\": {\n"
                + "      \"coreItem\": \"" + UUID.randomUUID() + "\",\n"
                + "      \"world\": \"world\", \"x\": 1, \"y\": 64, \"z\": -1,\n"
                + "      \"tier\": 1,\n"
                + "      \"modules\": {},\n"
                + "      \"bunker\": 0,\n"
                + "      \"lastCalcMillis\": 0,\n"
                + "      \"rebirths\": -1\n"
                + "    }\n"
                + "  }\n"
                + "}", StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertFalse(store.healthy(), "отрицательный счётчик — карантин");
    }

    @Test
    void moneyAccumulatesAndPayoutIsAtomic(@TempDir Path dir) throws Exception {
        GeneratorStore store = ready(dir);
        UUID owner = UUID.randomUUID();
        store.install(owner, UUID.randomUUID(), "world", 0, 0, 0, 0L);

        assertEquals(120L, store.addMoney(owner, 120L));
        assertTrue(store.takePayout(owner, 100L, 20_000L), "выплата списывает и фиксирует день");
        assertEquals(20L, store.entry(owner).money());
        assertEquals(20_000L, store.entry(owner).payoutDay());
        assertFalse(store.takePayout(owner, 10L, 20_000L), "повтор в тот же день запрещён");
        assertFalse(store.takePayout(owner, 500L, 20_001L), "больше копилки не снять");
        assertTrue(store.takePayout(owner, 20L, 20_001L), "следующий день — можно");
        assertThrows(IllegalArgumentException.class, () -> store.addMoney(owner, -1L),
                "минус в копилке недопустим");

        store.saveNow();
        GeneratorStore reloaded = newStore(dir);
        reloaded.load();
        assertTrue(reloaded.healthy());
        assertEquals(0L, reloaded.entry(owner).money());
        assertEquals(20_001L, reloaded.entry(owner).payoutDay());
    }

    @Test
    void legacyRowWithoutMoneyLoadsAsZero(@TempDir Path dir) throws Exception {
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve("generator.json"), "{\n"
                + "  \"schema\": 1,\n"
                + "  \"lineage\": \"" + UUID.randomUUID() + "\",\n"
                + "  \"registered\": true,\n"
                + "  \"players\": {\n"
                + "    \"" + owner + "\": {\n"
                + "      \"coreItem\": \"" + UUID.randomUUID() + "\",\n"
                + "      \"world\": \"world\", \"x\": 1, \"y\": 64, \"z\": -1,\n"
                + "      \"tier\": 2,\n"
                + "      \"modules\": {},\n"
                + "      \"bunker\": 5,\n"
                + "      \"lastCalcMillis\": 1000\n"
                + "    }\n"
                + "  }\n"
                + "}", StandardCharsets.UTF_8);
        GeneratorStore store = newStore(dir);
        store.load();
        assertTrue(store.healthy(), "файлы без денежных полей читаются");
        assertEquals(0L, store.entry(owner).money());
        assertEquals(0L, store.entry(owner).payoutDay());
    }
}
