package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorContainerStoreTest {
    @TempDir Path dir;
    private final UUID owner = UUID.randomUUID();
    private final UUID core = UUID.randomUUID();
    private final UUID tx = UUID.randomUUID();
    private GeneratorContainerStore open() {
        var store = new GeneratorContainerStore(dir, Logger.getAnonymousLogger());
        store.load();
        assertTrue(store.healthy());
        return store;
    }
    private static final class Bag implements InventoryTransfer.Inventory {
        final UUID owner;
        String state = "before", witness;
        int applications, checkpoints;
        boolean online = true;
        Bag(UUID owner) { this.owner = owner; }
        public UUID owner() { return owner; }
        public boolean available() { return online; }
        public String witness() { return witness; }
        public boolean matches(String snapshot) { return state.equals(snapshot); }
        public void apply(String after, String token) { state = after; witness = token; applications++; }
        public void checkpoint() { checkpoints++; }
    }

    @Test void productionStateIsAtomicAndOldRevisionCannotOverwrite() {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        var before = store.view(owner, 0);
        var slots = new java.util.ArrayList<>(before.slots());
        slots.set(0, new GeneratorContainerStore.Stack("COBBLESTONE", 40));
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 3));
        assertTrue(store.settle(owner, 0, core, before.revision(), slots, 80_000, 500));
        assertFalse(store.settle(owner, 0, core, before.revision(), before.slots(), 0, 600));
        var restored = open().view(owner, 0);
        assertEquals(slots, restored.slots());
        assertEquals(80_000, restored.burnMillis());
        assertEquals(500, restored.lastCalcMillis());
        assertEquals(1, restored.revision());
        assertFalse(store.create(owner, 0, UUID.randomUUID(), 100));
        assertFalse(store.create(owner, 1, core, 100));
    }

    @Test void depositAndWithdrawalSurviveEveryCheckpoint() throws Exception {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        assertTrue(store.prepare(owner, 0, core, 0, tx, 36, "COAL", 4, "before", "after"));
        assertTrue(open().view(owner, 0).pending());
        var bag = new Bag(owner);
        assertEquals(InventoryTransfer.Decision.APPLIED, open().recover(owner, 0, bag));
        assertEquals(1, bag.applications);
        store = open();
        assertFalse(store.view(owner, 0).pending());
        assertEquals(new GeneratorContainerStore.Stack("COAL", 4), store.view(owner, 0).slots().get(36));
        assertEquals(InventoryTransfer.Decision.APPLIED, store.recover(owner, 0, bag));
        assertEquals(1, bag.applications);

        var stock = new java.util.ArrayList<>(store.view(owner, 0).slots());
        stock.set(0, new GeneratorContainerStore.Stack("STONE", 10));
        assertTrue(store.settle(owner, 0, core, 1, stock, 0, 100));
        var tx2 = UUID.randomUUID();
        bag.state = "before";
        assertTrue(store.prepare(owner, 0, core, 2, tx2, 0, "STONE", -3, "before", "after"));
        assertEquals(InventoryTransfer.Decision.APPLIED, open().recover(owner, 0, bag));
        assertEquals(new GeneratorContainerStore.Stack("STONE", 7), open().view(owner, 0).slots().get(0));
        assertEquals(2, bag.applications);
    }

    @Test void witnessRecoveryNeverAppliesInventoryTwice() throws Exception {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        assertTrue(store.prepare(owner, 0, core, 0, tx, 36, "COAL", 3, "before", "after"));
        var bag = new Bag(owner);
        bag.state = "after";
        bag.witness = tx.toString();
        assertEquals(InventoryTransfer.Decision.APPLIED, open().recover(owner, 0, bag));
        assertEquals(0, bag.applications);
        assertEquals(1, bag.checkpoints);
        assertEquals(new GeneratorContainerStore.Stack("COAL", 3), open().view(owner, 0).slots().get(36));
    }

    @Test void pendingConflictBlocksProductionAndOtherTransfers() throws Exception {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        assertTrue(store.create(owner, 1, UUID.randomUUID(), 100));
        assertTrue(store.prepare(owner, 0, core, 0, tx, 36, "COAL", 4, "before", "after"));
        var view = store.view(owner, 0);
        assertFalse(store.settle(owner, 0, core, 0, view.slots(), 0, 200));
        assertFalse(store.prepare(owner, 1, store.view(owner, 1).core(), 0, UUID.randomUUID(), 36, "COAL", 1, "a", "b"));
        var bag = new Bag(owner);
        bag.state = "unrelated";
        assertEquals(InventoryTransfer.Decision.CONFLICT, store.recover(owner, 0, bag));
        assertTrue(open().view(owner, 0).pending());
        assertNull(open().view(owner, 0).slots().get(36));
    }

    @Test void diskFailureDoesNotPublishAndAfterWitnessCanRetry() throws Exception {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        Path tmp = dir.resolve("generator-containers.json.tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("block"), "x");
        assertFalse(store.prepare(owner, 0, core, 0, tx, 36, "COAL", 4, "before", "after"));
        assertFalse(open().view(owner, 0).pending());
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertTrue(store.prepare(owner, 0, core, 0, tx, 36, "COAL", 4, "before", "after"));
        var bag = new Bag(owner);
        Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x");
        assertThrows(IOException.class, () -> store.recover(owner, 0, bag));
        assertEquals(1, bag.applications);
        assertTrue(open().view(owner, 0).pending());
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertEquals(InventoryTransfer.Decision.APPLIED, open().recover(owner, 0, bag));
        assertEquals(1, bag.applications);
        assertEquals(new GeneratorContainerStore.Stack("COAL", 4), open().view(owner, 0).slots().get(36));
    }

    @Test void damagedDataStaysInQuarantine() throws Exception {
        var store = open();
        assertTrue(store.create(owner, 0, core, 100));
        Path file = dir.resolve("generator-containers.json");
        String damaged = Files.readString(file).replace("\"burnMillis\": 0", "\"burnMillis\": -1");
        Files.writeString(file, damaged);
        var restored = new GeneratorContainerStore(dir, Logger.getAnonymousLogger());
        restored.load();
        assertFalse(restored.healthy());
        assertFalse(restored.create(owner, 1, UUID.randomUUID(), 1));
        assertEquals(damaged, Files.readString(file));
    }
}
