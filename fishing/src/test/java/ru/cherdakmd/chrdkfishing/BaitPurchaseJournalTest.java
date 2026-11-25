package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BaitPurchaseJournalTest {
    @TempDir Path dir;
    final UUID lineage = UUID.randomUUID(), owner = UUID.randomUUID();
    final byte[] key = new byte[32];
    Path file() { return dir.resolve("bait.db"); }
    BaitToken.Token bait() { return BaitToken.issue(key, UUID.randomUUID(), "quick"); }

    static class Bank implements RodUpgradeJournal.Bank {
        long cursor;
        int attempts, payments;
        boolean unavailable, reject, throwAfterCommit;
        RodUpgradeJournal.Charge receipt;
        public long cursor(UUID lineage) { return unavailable ? -1 : cursor; }
        public RodUpgradeJournal.Status status(RodUpgradeJournal.Charge charge) {
            if (unavailable) return RodUpgradeJournal.Status.UNAVAILABLE;
            if (cursor < charge.sequence()) return RodUpgradeJournal.Status.MISSING;
            return charge.equals(receipt) ? RodUpgradeJournal.Status.COMMITTED : RodUpgradeJournal.Status.CONFLICT;
        }
        public void charge(RodUpgradeJournal.Charge charge) throws IOException {
            attempts++;
            if (reject) return;
            assertEquals(cursor + 1, charge.sequence());
            receipt = charge; cursor++; payments++;
            if (throwAfterCommit) throw new IOException("uncertain debit");
        }
    }
    static class Inventory implements RodUpgradeJournal.Inventory {
        UUID witness;
        int deliveries;
        boolean throwAfterDelivery;
        public RodUpgradeJournal.Transfer recover(UUID owner, UUID tx, String before, String after) throws IOException {
            if (tx.equals(witness)) return RodUpgradeJournal.Transfer.APPLIED;
            witness = tx; deliveries++;
            if (throwAfterDelivery) { throwAfterDelivery = false; throw new IOException("uncertain delivery"); }
            return RodUpgradeJournal.Transfer.APPLIED;
        }
    }
    RodUpgradeJournal.Result prepare(BaitPurchaseJournal journal, Bank bank, BaitToken.Token token) throws Exception {
        return journal.prepare(owner, UUID.randomUUID(), token, "before", "after", bank);
    }
    @Test void preparedPurchaseNeverAutomaticallyChargesAfterRestartAndCanBeCancelled() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var token = bait();
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, token));
        }
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, false)) {
            for (int i = 0; i < 3; i++) assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, false));
            assertEquals(0, bank.attempts); assertEquals(0, inventory.deliveries);
            assertEquals(owner, journal.pendingOwner());
            assertEquals(RodUpgradeJournal.Result.DONE, journal.cancelUnpaid(bank));
            assertFalse(journal.available(token)); assertNull(journal.pendingOwner());
        }
    }
    @Test void rejectedChargeIsNotRetriedInBackground() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); bank.reject = true;
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, bait()));
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, true));
            bank.reject = false;
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, false));
            assertEquals(1, bank.attempts); assertEquals(0, bank.payments); assertEquals(0, inventory.deliveries);
            assertEquals(RodUpgradeJournal.Result.DONE, journal.cancelUnpaid(bank));
        }
    }
    @Test void committedPaymentAndUncertainDeliveryRecoverOnceAcrossRestarts() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var token = bait(); bank.throwAfterCommit = true;
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, token));
            assertThrows(IOException.class, () -> journal.recover(bank, inventory, true));
        }
        inventory.throwAfterDelivery = true;
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, false)) {
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertThrows(IOException.class, () -> journal.recover(bank, inventory, false));
            assertFalse(journal.available(token));
        }
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, false)) {
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory, false));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory, false));
            assertNull(journal.pendingOwner()); assertTrue(journal.available(token));
            assertEquals(1, bank.attempts); assertEquals(1, bank.payments); assertEquals(1, inventory.deliveries);
        }
    }
    @Test void temporarilyUnavailableBankDoesNotCancelOrRedebitAnAlreadyPaidIntent() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var token = bait(); bank.throwAfterCommit = true;
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, token));
            assertThrows(IOException.class, () -> journal.recover(bank, inventory, true));
        }
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, false)) {
            bank.unavailable = true;
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.cancelUnpaid(bank));
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, false));
            assertEquals(0, inventory.deliveries); assertEquals(1, bank.payments);
            bank.unavailable = false;
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory, false));
            assertEquals(1, bank.attempts); assertEquals(1, inventory.deliveries); assertTrue(journal.available(token));
        }
    }
    @Test void unavailableBankDoesNotCreateIntentOrAuthorizeCancellation() throws Exception {
        var bank = new Bank(); bank.unavailable = true; var token = bait();
        try (var journal = new BaitPurchaseJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, token));
            assertNull(journal.pendingOwner());
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.cancelUnpaid(bank));
            assertEquals(0, bank.attempts); assertFalse(journal.available(token));
        }
    }
}
