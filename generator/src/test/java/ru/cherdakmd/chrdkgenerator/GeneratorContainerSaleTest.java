package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorContainerSaleTest {
    @TempDir Path dir;
    UUID owner = UUID.randomUUID(), core = UUID.randomUUID();
    static class Ledger implements GeneratorContainerSale.Ledger {
        final Map<String, Long> receipts = new HashMap<>();
        boolean accepting = true;
        public Map<String, Long> reservations(UUID owner) { return Map.copyOf(receipts); }
        public boolean accept(String key, long amount) {
            if (!accepting) return false;
            receipts.putIfAbsent(key, amount); return true;
        }
    }
    GeneratorContainerStore open() {
        var store = new GeneratorContainerStore(dir, Logger.getAnonymousLogger()); store.load(); assertTrue(store.healthy()); return store;
    }
    GeneratorContainerStore ready() {
        var s = open(); assertTrue(s.create(owner, 0, core, 0));
        var slots = new java.util.ArrayList<>(s.view(owner, 0).slots());
        slots.set(0, new GeneratorContainerStore.Stack("DIAMOND", 64));
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 8));
        assertTrue(s.settle(owner, 0, core, 0, slots, 0, 0)); return s;
    }
    GeneratorContainerSale.Result run(GeneratorContainerStore s, Ledger l, boolean sell) {
        return GeneratorContainerSale.run(s, owner, 0, sell, (material, n) -> n * 20_000L, l);
    }
    @Test void saleFreezesItemsAndAmountBeforeCreditAndRetries() {
        var s = ready(); var ledger = new Ledger(); ledger.accepting = false;
        assertEquals(GeneratorContainerSale.Result.WAIT, run(s, ledger, true));
        var view = open().view(owner, 0);
        assertEquals(14, view.slots().get(0).amount());
        assertEquals(8, view.slots().get(36).amount());
        assertEquals(1_000_000, open().saleState(owner, 0).pending().amount());
        ledger.accepting = true;
        assertEquals(GeneratorContainerSale.Result.SOLD, run(open(), ledger, false));
        assertEquals(GeneratorContainerSale.Result.IDLE, run(open(), ledger, false));
        assertEquals(1, ledger.receipts.size());
    }
    @Test void restartAfterBankReceiptAndFailedLocalCommitDoesNotPayTwice() throws Exception {
        var s = ready(); var ledger = new Ledger(); ledger.accepting = false;
        assertEquals(GeneratorContainerSale.Result.WAIT, run(s, ledger, true));
        ledger.accepting = true;
        var tmp = dir.resolve("generator-containers.json.tmp"); Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x");
        assertEquals(GeneratorContainerSale.Result.WAIT, run(open(), ledger, false));
        assertEquals(1, ledger.receipts.size());
        assertNotNull(open().saleState(owner, 0).pending());
        Files.delete(tmp.resolve("block")); Files.delete(tmp);
        assertEquals(GeneratorContainerSale.Result.SOLD, run(open(), ledger, false));
        assertEquals(1, ledger.receipts.size());
        assertNull(open().saleState(owner, 0).pending());
    }
    @Test void restoredOldContainerOrLostBankHistoryStops() throws Exception {
        var s = ready(); var ledger = new Ledger();
        Path backup = dir.resolve("backup"); Files.copy(dir.resolve("generator-containers.json"), backup);
        assertEquals(GeneratorContainerSale.Result.SOLD, run(s, ledger, true));
        var sold = Files.readString(dir.resolve("generator-containers.json"));
        Files.copy(backup, dir.resolve("generator-containers.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(GeneratorContainerSale.Result.CONFLICT, run(open(), ledger, true));
        Files.writeString(dir.resolve("generator-containers.json"), sold);
        ledger.receipts.clear();
        assertEquals(GeneratorContainerSale.Result.CONFLICT, run(open(), ledger, true));
    }
    @Test void mismatchingExistingReceiptIsNeverAccepted() {
        var s = ready(); var ledger = new Ledger(); ledger.accepting = false;
        assertEquals(GeneratorContainerSale.Result.WAIT, run(s, ledger, true));
        ledger.receipts.put(GeneratorContainerSale.key(core, 1), 2L);
        ledger.accepting = true;
        assertEquals(GeneratorContainerSale.Result.CONFLICT, run(open(), ledger, true));
        assertNotNull(open().saleState(owner, 0).pending());
    }
}
