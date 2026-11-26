package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FishSaleJournalTest {
    @TempDir Path dir;
    final UUID owner = UUID.randomUUID(), transaction = UUID.randomUUID();
    final byte[] key = new byte[32];
    FishToken.Token fish() {
        return FishToken.issue(key, UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 100);
    }
    static final class Bank implements FishSaleJournal.Bank {
        final Map<UUID, Map<String, Long>> accounts = new HashMap<>();
        boolean reject, failAfterAccept;
        int attempts;
        public Map<String, Long> receipts(UUID owner) { return Map.copyOf(accounts.getOrDefault(owner, Map.of())); }
        public boolean accept(UUID owner, String key, long amount) throws IOException {
            attempts++;
            if (reject) return false;
            var map = accounts.computeIfAbsent(owner, ignored -> new HashMap<>());
            Long existing = map.putIfAbsent(key, amount);
            if (existing != null && existing != amount) return false;
            if (failAfterAccept) { failAfterAccept = false; throw new IOException("uncertain bank response"); }
            return true;
        }
    }
    static final class Bag implements FishSaleJournal.Inventory {
        UUID witness; String state = "before"; int removals; boolean failAfterApply, available = true;
        public FishSaleJournal.TransferResult recover(UUID owner, UUID transaction, String before, String after) throws IOException {
            if (!available) return FishSaleJournal.TransferResult.WAIT;
            if (transaction.equals(witness)) return FishSaleJournal.TransferResult.APPLIED;
            if (!state.equals(before)) return FishSaleJournal.TransferResult.CONFLICT;
            state = after; witness = transaction; removals++;
            if (failAfterApply) { failAfterApply = false; throw new IOException("uncertain playerdata result"); }
            return FishSaleJournal.TransferResult.APPLIED;
        }
    }
    Path file() { return dir.resolve("fishing-sales.db"); }
    @Test void uncertainPlayerAndBankWritesRecoverWithoutDuplicateCharge() throws Exception {
        var bank = new Bank(); var bag = new Bag(); var fish = fish(); bag.failAfterApply = true;
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish), key, bank));
            assertThrows(IOException.class, () -> j.recover(owner, transaction, bag, bank));
            assertEquals(transaction, j.pending(owner));
            assertTrue(bank.receipts(owner).isEmpty());
        }
        bank.failAfterAccept = true;
        try (var j = new FishSaleJournal(file(), false)) {
            assertThrows(IOException.class, () -> j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals); assertEquals(100L, bank.receipts(owner).get(FishSaleJournal.receiptKey(transaction)).longValue());
        }
        try (var j = new FishSaleJournal(file(), false)) {
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertNull(j.pending(owner));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals); assertEquals(1, bank.receipts(owner).size());
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(UUID.randomUUID(), UUID.randomUUID(), "before", "after", List.of(fish), key, bank));
        }
    }
    @Test void noCreditWithoutWitnessAndOwnerCanHaveOnlyOnePendingSale() throws Exception {
        var bank = new Bank(); bank.reject = true; var bag = new Bag(); bag.available = false;
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, UUID.randomUUID(), "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.WAIT, j.recover(owner, transaction, bag, bank));
            assertTrue(bank.receipts(owner).isEmpty());
            bag.available = true;
            assertEquals(FishSaleJournal.Result.WAIT, j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals); assertTrue(bank.receipts(owner).isEmpty());
            bank.reject = false;
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals);
        }
    }
    @Test void duplicateOrForgedTokensAndHistoryMismatchAreRejected() throws Exception {
        var bank = new Bank(); var fish = fish();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, transaction, "before", "after", List.of(fish, fish), key, bank));
            var forged = new FishToken.Token(fish.id(), fish.fish(), 200, fish.mac());
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, transaction, "before", "after", List.of(forged), key, bank));
            bank.accounts.computeIfAbsent(owner, ignored -> new HashMap<>()).put(FishSaleJournal.receiptKey(UUID.randomUUID()), 100L);
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, transaction, "before", "after", List.of(fish), key, bank));
        }
    }
    @Test void missingOrInvalidJournalIsNotSilentlyReinitialized() throws Exception {
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), false));
        Files.writeString(file(), "not a database");
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), true));
        assertEquals("not a database", Files.readString(file()));
    }
    @Test void lostBankReceiptStopsFutureSales() throws Exception {
        var bank = new Bank(); var bag = new Bag();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            bank.accounts.clear();
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepare(owner, UUID.randomUUID(), "before", "after", List.of(fish()), key, bank));
        }
    }
    @Test void receiptAheadOfPreparedJournalStopsBeforeRemovingFishOrPaying() throws Exception {
        var bank = new Bank(); var bag = new Bag();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
        }
        bank.accounts.computeIfAbsent(owner, ignored -> new HashMap<>()).put(FishSaleJournal.receiptKey(transaction), 100L);
        try (var j = new FishSaleJournal(file(), false)) {
            assertFalse(j.historyMatchesAll(bank.accounts));
            assertEquals(FishSaleJournal.Result.CONFLICT, j.recover(owner, transaction, bag, bank));
            assertEquals(transaction, j.pending(owner));
            assertEquals(0, bag.removals); assertEquals(0, bank.attempts);
        }
    }
    @Test void wrongAmountAfterUncertainCreditQuarantinesRecoveryAcrossRestart() throws Exception {
        var bank = new Bank(); var bag = new Bag(); bank.failAfterAccept = true;
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
            assertThrows(IOException.class, () -> j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals); assertEquals(1, bank.attempts);
        }
        bank.accounts.get(owner).put(FishSaleJournal.receiptKey(transaction), 101L);
        try (var j = new FishSaleJournal(file(), false)) {
            assertFalse(j.historyMatchesAll(bank.accounts));
            for (int i = 0; i < 3; i++)
                assertEquals(FishSaleJournal.Result.CONFLICT, j.recover(owner, transaction, bag, bank));
            assertEquals(transaction, j.pending(owner));
            assertEquals(1, bag.removals); assertEquals(1, bank.attempts);
        }
    }
    @Test void globalReconciliationDetectsExtraOwnersAndMissingReceipts() throws Exception {
        var bank = new Bank(); var bag = new Bag();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertTrue(j.historyMatchesAll(bank.accounts));
            var other = UUID.randomUUID();
            bank.accounts.put(other, Map.of(FishSaleJournal.receiptKey(UUID.randomUUID()), 100L));
            assertFalse(j.historyMatchesAll(bank.accounts));
            bank.accounts.remove(other);
            bank.accounts.remove(owner);
            assertFalse(j.historyMatchesAll(bank.accounts));
        }
    }
    @Test void mixedSaleRecoversUncertainTransferAndBankCreditOnlyOnce() throws Exception {
        var bank = new Bank(); var bag = new Bag(); bag.failAfterApply = true; var token = fish();
        var vanilla = List.of(new FishSaleJournal.VanillaFish(1, "COD", 64), new FishSaleJournal.VanillaFish(7, "SALMON", 3));
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepareMixed(owner, transaction, "before", "after", List.of(token), vanilla, key, bank));
            assertThrows(IOException.class, () -> j.recover(owner, transaction, bag, bank));
            assertEquals(0, bank.attempts);
        }
        bank.failAfterAccept = true;
        try (var j = new FishSaleJournal(file(), false)) {
            assertThrows(IOException.class, () -> j.recover(owner, transaction, bag, bank));
            assertEquals(770L, bank.receipts(owner).get(FishSaleJournal.receiptKey(transaction)));
        }
        try (var j = new FishSaleJournal(file(), false)) {
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertEquals(1, bag.removals); assertEquals(1, bank.receipts(owner).size());
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepareMixed(owner, UUID.randomUUID(), "before", "after", List.of(token), List.of(), key, bank));
        }
    }
    @Test void vanillaOnlySaleAndMissingStackRecordAreHandled() throws Exception {
        var bank = new Bank(); var bag = new Bag();
        try (var j = new FishSaleJournal(file(), true)) {
            var stack = new FishSaleJournal.VanillaFish(1, "PUFFERFISH", 4);
            assertEquals(FishSaleJournal.Result.CONFLICT, j.prepareMixed(owner, UUID.randomUUID(), "before", "after", List.of(), List.of(stack, stack), key, bank));
            assertEquals(FishSaleJournal.Result.WAIT, j.prepareMixed(owner, transaction, "before", "after", List.of(), List.of(stack), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
            assertEquals(40L, bank.receipts(owner).get(FishSaleJournal.receiptKey(transaction)));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement()) {
            stmt.execute("DELETE FROM vanilla_fish");
        }
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), false));
    }
    @Test void mixedSaleMissingSignedIdentityIsQuarantined() throws Exception {
        var bank = new Bank();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepareMixed(owner, transaction, "before", "after", List.of(fish()),
                    List.of(new FishSaleJournal.VanillaFish(1, "COD", 2)), key, bank));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement()) {
            stmt.execute("DELETE FROM spent_fish");
        }
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), false));
    }
    @Test void versionFourMigratesWithoutLosingExistingSales() throws Exception {
        var bank = new Bank(); var bag = new Bag();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement()) {
            stmt.execute("DROP TABLE vanilla_fish"); stmt.execute("ALTER TABLE sales DROP COLUMN vanilla_count");
            stmt.execute("ALTER TABLE sales DROP COLUMN signed_count"); stmt.execute("PRAGMA user_version=4");
        }
        try (var j = new FishSaleJournal(file(), false)) {
            assertTrue(j.historyMatchesAll(bank.accounts));
            assertEquals(FishSaleJournal.Result.DONE, j.recover(owner, transaction, bag, bank));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement(); var rs = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next()); assertEquals(5, rs.getInt(1));
        }
    }
    @Test void alteredActiveSaleIndexIsQuarantined() throws Exception {
        try (var j = new FishSaleJournal(file(), true)) { }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement()) {
            stmt.execute("DROP INDEX active_sale_owner");
            stmt.execute("CREATE INDEX active_sale_owner ON sales(owner)");
        }
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), false));
    }
    @Test void emptyRowsAndLostFishReferencesAreQuarantined() throws Exception {
        var bank = new Bank();
        try (var j = new FishSaleJournal(file(), true)) {
            assertEquals(FishSaleJournal.Result.WAIT, j.prepare(owner, transaction, "before", "after", List.of(fish()), key, bank));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file()); var stmt = db.createStatement()) {
            stmt.execute("DELETE FROM spent_fish");
        }
        assertThrows(java.sql.SQLException.class, () -> new FishSaleJournal(file(), false));
    }

}
