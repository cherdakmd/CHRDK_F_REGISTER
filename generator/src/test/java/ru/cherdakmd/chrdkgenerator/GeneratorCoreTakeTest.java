package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorCoreTakeTest {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID(), core = UUID.randomUUID(), tx = UUID.randomUUID();
    GeneratorStore open() { var s = new GeneratorStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    static class Bag implements InventoryTransfer.Inventory {
        UUID owner; String state = "before", witness; int applications;
        Bag(UUID owner) { this.owner = owner; }
        public UUID owner() { return owner; }
        public boolean available() { return true; }
        public String witness() { return witness; }
        public boolean matches(String snapshot) { return state.equals(snapshot); }
        public void apply(String after, String token) { state = after; witness = token; applications++; }
        public void checkpoint() { }
    }
    @Test void witnessRecoveryDoesNotDeliverCoreTwice() throws Exception {
        var s = open(); s.markRegistered(); s.install(owner, core, "world", 0, 64, 0, 1, 3); s.flush();
        assertTrue(s.prepareCoreTake(owner, 0, new GeneratorStore.CoreTake(tx, core, "before", "after")));
        assertThrows(IllegalStateException.class, () -> s.remove(owner, 0, 2));
        var bag = new Bag(owner);
        var transfer = new InventoryTransfer.Intent(tx, owner, "before", "after");
        assertEquals(InventoryTransfer.Decision.APPLIED, InventoryTransfer.recover(transfer, bag));
        Path tmp = dir.resolve("generator.json.tmp"); Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x");
        assertFalse(s.finishCoreTake(owner, 0, tx, 2));
        assertNotNull(open().coreTake(owner, 0));
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertEquals(InventoryTransfer.Decision.APPLIED, InventoryTransfer.recover(transfer, bag));
        assertEquals(1, bag.applications);
        assertTrue(open().finishCoreTake(owner, 0, tx, 2));
        assertFalse(open().entry(owner, 0).installed());
    }
}
