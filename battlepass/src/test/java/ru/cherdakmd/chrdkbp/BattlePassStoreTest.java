package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class BattlePassStoreTest {

    @Test
    void progressCapsAtTargetAndClaimsStick(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        BattlePassStore store = new BattlePassStore(tempDir, java.util.logging.Logger.getAnonymousLogger());
        store.load();
        assertEquals(3, store.addProgress(uuid, "m1", 3, 5));
        assertEquals(5, store.addProgress(uuid, "m1", 10, 5), "лишнее не накапливается сверх цели");
        assertEquals(5, store.progressOf(uuid, "m1"));

        assertTrue(store.markClaimed(uuid, "m1", "FREE"));
        assertFalse(store.markClaimed(uuid, "m1", "FREE"), "повторная выдача не пройдёт");
        assertTrue(store.markClaimed(uuid, "m1", "VIP"), "VIP-ветка независима");
        store.save();

        BattlePassStore reloaded = new BattlePassStore(tempDir, java.util.logging.Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals(5, reloaded.progressOf(uuid, "m1"));
        assertTrue(reloaded.isClaimed(uuid, "m1", "FREE"));
        assertTrue(reloaded.isClaimed(uuid, "m1", "VIP"));
        assertFalse(reloaded.isClaimed(uuid, "m1", "LEGEND"));
    }

    @Test
    void namesAndWaveSurviveRestart(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        BattlePassStore store = new BattlePassStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        store.rememberName(uuid, "Steve");
        store.setLastWave(uuid, 2);
        store.addProgress(uuid, "m1", 1, 5);
        store.save();

        BattlePassStore reloaded = new BattlePassStore(tempDir, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals("Steve", reloaded.nameOf(uuid));
        assertEquals(2, reloaded.lastWave(uuid));
        assertEquals(java.util.Map.of(uuid, "Steve"), reloaded.namesSnapshot());
        assertEquals(1, reloaded.progressMap(uuid).get("m1"));
    }

    @Test
    void oldFormatWithoutNamesStillLoads(@TempDir Path tempDir) throws Exception {
        UUID uuid = UUID.randomUUID();
        java.nio.file.Files.writeString(tempDir.resolve("battlepass-data.json"),
                "{\"" + uuid + "\":{\"progress\":{\"m1\":3},\"claimed\":[\"m1:FREE\"]}}");
        BattlePassStore store = new BattlePassStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        assertEquals(3, store.progressOf(uuid, "m1"));
        assertTrue(store.isClaimed(uuid, "m1", "FREE"));
        assertEquals("", store.nameOf(uuid));
        assertEquals(0, store.lastWave(uuid));
    }

    @Test
    void resetAllClearsSeasonAndCountsPlayers(@TempDir Path tempDir) {
        BattlePassStore store = new BattlePassStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.addProgress(first, "m1", 1, 5);
        store.addProgress(second, "m2", 2, 5);
        assertEquals(2, store.resetAll());
        assertFalse(store.known(first));
        assertEquals(0, store.resetAll(), "повторный сброс не считает никого");
    }

    @Test
    void setProgressClampsToTarget(@TempDir Path tempDir) {
        BattlePassStore store = new BattlePassStore(tempDir, Logger.getAnonymousLogger());
        store.load();
        UUID uuid = UUID.randomUUID();
        assertEquals(5, store.setProgress(uuid, "m1", 99, 5));
        assertEquals(0, store.setProgress(uuid, "m1", -3, 5));
        assertEquals(2, store.setProgress(uuid, "m1", 2, 5));
        assertEquals(2, store.progressOf(uuid, "m1"));
    }

    @Test
    void resetRestoresFreshState(@TempDir Path tempDir) {
        UUID uuid = UUID.randomUUID();
        BattlePassStore store = new BattlePassStore(tempDir, java.util.logging.Logger.getAnonymousLogger());
        store.load();
        store.addProgress(uuid, "m1", 5, 5);
        store.markClaimed(uuid, "m1", "FREE");
        assertTrue(store.known(uuid));
        store.reset(uuid);
        assertFalse(store.known(uuid));
        assertEquals(0, store.progressOf(uuid, "m1"));
        assertTrue(store.markClaimed(uuid, "m1", "FREE"));
    }
}
