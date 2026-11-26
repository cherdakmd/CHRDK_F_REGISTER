package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import ru.cherdakmd.chrdkregister.BankStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite + Core bank file + production adapter. Playerdata and failure timing are simulated. */
class FishingBankRecoveryIntegrationTest {
    @TempDir Path directory;
    private final UUID lineage = UUID.randomUUID(), owner = UUID.randomUUID(), transaction = UUID.randomUUID();
    private final byte[] key = new byte[32];
    private static final String SOURCE = "fishing-horizons-sale";
    private Path database() { return directory.resolve("sales.db"); }
    private BankStore reload() {
        var bank = new BankStore(directory, Logger.getAnonymousLogger()); bank.load(); return bank;
    }
    private BankStore initialize() {
        var bank = reload(); assertTrue(bank.registerIncomeSource(SOURCE, lineage)); return bank;
    }
    public interface Api {
        boolean incomeSourceAvailable(String source, UUID lineage);
        Map<String, Long> incomeReceipts(String source, UUID lineage, UUID owner);
        Map<UUID, Map<String, Long>> incomeSourceReceipts(String source, UUID lineage);
        long creditedEntitlement(String source, UUID lineage, String key, UUID owner);
        boolean creditEntitlementMinor(String source, UUID lineage, String key, UUID owner, long total);
    }
    private CoreSaleBank adapter(BankStore bank) throws Exception {
        Api api = new Api() {
            public boolean incomeSourceAvailable(String s, UUID l) { return bank.incomeSourceAvailable(s, l); }
            public Map<String, Long> incomeReceipts(String s, UUID l, UUID o) { return bank.incomeReceipts(s, l, o); }
            public Map<UUID, Map<String, Long>> incomeSourceReceipts(String s, UUID l) { return bank.incomeSourceReceipts(s, l); }
            public long creditedEntitlement(String s, UUID l, String k, UUID o) { return bank.creditedEntitlement(s, l, k, o); }
            public boolean creditEntitlementMinor(String s, UUID l, String k, UUID o, long amount) { return bank.creditEntitlementMinor(s, l, k, o, amount); }
        };
        var services = mock(ServicesManager.class); when(services.load(Api.class)).thenReturn(api);
        return new CoreSaleBank(services, Api.class, lineage, SOURCE);
    }
    private FishToken.Token fish() {
        return FishToken.issue(key, UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 100);
    }
    static final class Inventory implements FishSaleJournal.Inventory {
        String state = "before"; UUID witness; int removals; boolean failAfterTransfer;
        @Override public FishSaleJournal.TransferResult recover(UUID owner, UUID tx, String before, String after) throws IOException {
            if (tx.equals(witness)) return FishSaleJournal.TransferResult.APPLIED;
            if (!state.equals(before)) return FishSaleJournal.TransferResult.CONFLICT;
            state = after; witness = tx; removals++;
            if (failAfterTransfer) { failAfterTransfer = false; throw new IOException("simulated uncertain player save"); }
            return FishSaleJournal.TransferResult.APPLIED;
        }
    }
    @Test void mixedSaleSurvivesUncertainInventoryAndRealBankCreditWithoutSecondPayout() throws Exception {
        var bank = initialize(); var inventory = new Inventory(); inventory.failAfterTransfer = true;
        var token = fish(); var vanilla = List.of(new FishSaleJournal.VanillaFish(3, "COD", 3));
        try (var journal = new FishSaleJournal(database(), true)) {
            var adapter = adapter(bank);
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareMixed(owner, transaction, "before", "after", List.of(token), vanilla, key, adapter));
            assertThrows(IOException.class, () -> journal.recover(owner, transaction, inventory, adapter));
            assertEquals(0, bank.balanceMinor(owner));
        }
        bank = reload(); var real = adapter(bank);
        FishSaleJournal.Bank uncertainBank = new FishSaleJournal.Bank() {
            public Map<String, Long> receipts(UUID o) throws Exception { return real.receipts(o); }
            public boolean accept(UUID o, String id, long amount) throws Exception {
                assertTrue(real.accept(o, id, amount)); throw new IOException("simulated lost response after real bank credit");
            }
        };
        try (var journal = new FishSaleJournal(database(), false)) {
            assertThrows(IOException.class, () -> journal.recover(owner, transaction, inventory, uncertainBank));
            assertEquals(130, reload().balanceMinor(owner));
        }
        bank = reload(); var afterRestart = adapter(bank);
        try (var journal = new FishSaleJournal(database(), false)) {
            assertTrue(journal.historyMatchesAll(afterRestart.allReceipts()));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, afterRestart));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, afterRestart));
            assertEquals(1, inventory.removals); assertEquals(130, reload().balanceMinor(owner));
            assertEquals(Map.of(FishSaleJournal.receiptKey(transaction), 130L), afterRestart.receipts(owner));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.prepare(owner, UUID.randomUUID(), "before", "after", List.of(token), key, afterRestart));
        }
    }
    @Test void conflictingInventoryDoesNotCreditBank() throws Exception {
        var bank = initialize(); var adapter = adapter(bank); var inventory = new Inventory(); inventory.state = "changed";
        try (var journal = new FishSaleJournal(database(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareMixed(owner, transaction, "before", "after", List.of(),
                    List.of(new FishSaleJournal.VanillaFish(0, "SALMON", 1)), key, adapter));
            assertEquals(FishSaleJournal.Result.CONFLICT, journal.recover(owner, transaction, inventory, adapter));
            assertEquals(transaction, journal.pending(owner)); assertEquals(0, inventory.removals);
            assertTrue(adapter.receipts(owner).isEmpty()); assertEquals(0, reload().balanceMinor(owner));
        }
    }
    @Test void olderJournalSnapshotIsRejectedAgainstRealNewerBankHistory() throws Exception {
        var bank = initialize(); var adapter = adapter(bank); var inventory = new Inventory();
        try (var journal = new FishSaleJournal(database(), true)) { assertTrue(journal.historyMatchesAll(adapter.allReceipts())); }
        var old = directory.resolve("earlier.db"); Files.copy(database(), old);
        try (var journal = new FishSaleJournal(database(), false)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareMixed(owner, transaction, "before", "after", List.of(),
                    List.of(new FishSaleJournal.VanillaFish(0, "COD", 2)), key, adapter));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, adapter));
        }
        var restarted = adapter(reload());
        try (var earlier = new FishSaleJournal(old, false)) {
            assertFalse(earlier.historyMatchesAll(restarted.allReceipts()));
            assertEquals(FishSaleJournal.Result.CONFLICT, earlier.prepareMixed(owner, UUID.randomUUID(), "before", "after", List.of(),
                    List.of(new FishSaleJournal.VanillaFish(0, "COD", 2)), key, restarted));
            assertEquals(20, reload().balanceMinor(owner));
        }
    }
    @Test void offlineAuditorReconcilesRealCoreAndFishingFilesAndDetectsOldJournal() throws Exception {
        var bank = initialize(); var adapter = adapter(bank); var inventory = new Inventory();
        try (var journal = new FishSaleJournal(database(), true)) { assertTrue(journal.historyMatchesAll(adapter.allReceipts())); }
        Path previous = directory.resolve("old-sales.db"); Files.copy(database(), previous);
        try (var journal = new FishSaleJournal(database(), false)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareMixed(owner, transaction, "before", "after", List.of(fish()),
                    List.of(new FishSaleJournal.VanillaFish(4, "COD", 3)), key, adapter));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, adapter));
        }
        Path identity = directory.resolve("sale-lineage.txt"); Files.writeString(identity, lineage + "\n");
        byte[] bankBefore = Files.readAllBytes(bank.file()); byte[] salesBefore = Files.readAllBytes(database());
        var script = Path.of("fishing/tools/audit_sale_snapshot.py").toAbsolutePath().toString();
        var command = List.of("python3", script, "--bank", bank.file().toString(), "--sale", database().toString(),
                "--lineage", identity.toString(), "--source", SOURCE);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); assertTrue(output.contains("\"requires_review\": false"));
        assertFalse(output.contains(owner.toString())); assertFalse(output.contains(transaction.toString()));
        assertArrayEquals(bankBefore, Files.readAllBytes(bank.file())); assertArrayEquals(salesBefore, Files.readAllBytes(database()));
        var oldCommand = new java.util.ArrayList<>(command); oldCommand.set(5, previous.toString());
        process = new ProcessBuilder(oldCommand).redirectErrorStream(true).start();
        output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2, process.waitFor(), output); assertTrue(output.contains("\"requires_review\": true"));
    }
    @Test void fullBankWaitsAfterRemovalAndCreditsOnceWhenRoomIsAvailable() throws Exception {
        var bank = initialize(); assertEquals(BankStore.MAX_BALANCE, bank.deposit(owner, "owner", BankStore.MAX_BALANCE)); bank.saveNow();
        var adapter = adapter(bank); var inventory = new Inventory();
        try (var journal = new FishSaleJournal(database(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, journal.prepareMixed(owner, transaction, "before", "after", List.of(),
                    List.of(new FishSaleJournal.VanillaFish(0, "COD", 3)), key, adapter));
            assertEquals(FishSaleJournal.Result.WAIT, journal.recover(owner, transaction, inventory, adapter));
            assertEquals(1, inventory.removals); assertTrue(adapter.receipts(owner).isEmpty());
        }
        bank = reload(); assertTrue(bank.withdraw(owner, 1)); bank.saveNow();
        var afterRestart = adapter(reload());
        try (var journal = new FishSaleJournal(database(), false)) {
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, afterRestart));
            assertEquals(FishSaleJournal.Result.DONE, journal.recover(owner, transaction, inventory, afterRestart));
            assertEquals(1, inventory.removals);
            assertEquals(BankStore.MAX_BALANCE * 100L - 70, reload().balanceMinor(owner));
        }
    }
}
