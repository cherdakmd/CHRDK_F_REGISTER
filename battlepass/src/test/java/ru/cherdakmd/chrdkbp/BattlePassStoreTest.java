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
