package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RodUpgradeJournalTest {
    @TempDir Path dir;
    final UUID lineage = UUID.randomUUID(), owner = UUID.randomUUID(), rod = UUID.randomUUID();
    final byte[] key = new byte[32];

    Path file() { return dir.resolve("fishing-rods.db"); }
    RodToken.Token token(int level) { return RodToken.issue(key, rod, level); }

    static final class Bank implements RodUpgradeJournal.Bank {
        long sequence;
        RodUpgradeJournal.Charge receipt;
        int charges, attempts;
        boolean unavailable, failAfterCommit, rejectCharge;
        @Override public long cursor(UUID lineage) { return unavailable ? -1 : sequence; }
        @Override public RodUpgradeJournal.Status status(RodUpgradeJournal.Charge charge) {
            if (unavailable) return RodUpgradeJournal.Status.UNAVAILABLE;
            if (charge.sequence() > sequence || receipt == null) return RodUpgradeJournal.Status.MISSING;
            return receipt.equals(charge) ? RodUpgradeJournal.Status.COMMITTED : RodUpgradeJournal.Status.CONFLICT;
        }
        @Override public void charge(RodUpgradeJournal.Charge charge) throws IOException {
            if (charge.sequence() != sequence + 1) throw new AssertionError("duplicate or out-of-order debit");
            attempts++;
            if (rejectCharge) return;
            receipt = charge; sequence++; charges++;
            if (failAfterCommit) { failAfterCommit = false; throw new IOException("uncertain debit"); }
        }
    }

    static final class Inventory implements RodUpgradeJournal.Inventory {
        String state = "before";
        UUID witness;
        int transfers;
        boolean failAfterApply, unavailable;
        @Override public RodUpgradeJournal.Transfer recover(UUID owner, UUID transaction, String before, String after) throws IOException {
            if (unavailable) return RodUpgradeJournal.Transfer.WAIT;
            if (transaction.equals(witness)) return RodUpgradeJournal.Transfer.APPLIED;
            if (!state.equals(before)) return RodUpgradeJournal.Transfer.CONFLICT;
            state = after; witness = transaction; transfers++;
            if (failAfterApply) { failAfterApply = false; throw new IOException("uncertain playerdata save"); }
            return RodUpgradeJournal.Transfer.APPLIED;
        }
    }

    RodUpgradeJournal.Result prepare(RodUpgradeJournal journal, Bank bank, RodToken.Token previous, RodToken.Token next) throws Exception {
        return journal.prepare(owner, UUID.randomUUID(), previous, next, 100, "before", "after", bank);
    }

    @Test void uncertainDebitAndTransferRecoverAcrossRestartsWithoutRepeatingEither() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var next = token(2);
        bank.failAfterCommit = true;
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, next));
            assertFalse(journal.ready(bank));
            assertThrows(IOException.class, () -> journal.recover(bank, inventory));
            assertEquals(owner, journal.pendingOwner());
            assertFalse(journal.current(next));
        }
        inventory.failAfterApply = true;
        try (var journal = new RodUpgradeJournal(file(), lineage, key, false)) {
            assertThrows(IOException.class, () -> journal.recover(bank, inventory));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertEquals(1, bank.charges); assertEquals(1, inventory.transfers);
        }
        try (var journal = new RodUpgradeJournal(file(), lineage, key, false)) {
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory));
            assertNull(journal.pendingOwner());
            assertTrue(journal.ready(bank)); assertTrue(journal.current(next));
            assertEquals(1, bank.charges); assertEquals(1, inventory.transfers);
        }
    }

    @Test void existingUnpaidIntentWaitsAndCanBeCancelledWithoutAutomaticDebit() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var next = token(2);
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, next));
        }
        try (var journal = new RodUpgradeJournal(file(), lineage, key, false)) {
            for (int i = 0; i < 3; i++)
                assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, false));
            assertEquals(0, bank.charges); assertEquals(0, inventory.transfers);
            assertEquals(owner, journal.pendingOwner());
            assertEquals(RodUpgradeJournal.Result.DONE, journal.cancelUnpaid(bank));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory, false));
            assertNull(journal.pendingOwner()); assertFalse(journal.current(next));
            assertEquals(0, bank.charges); assertEquals(0, inventory.transfers);
        }
    }
    @Test void failedInitialDebitIsNotRetriedBehindPlayersBack() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); bank.rejectCharge = true;
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, token(2)));
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, true));
            assertEquals(1, bank.attempts); assertEquals(0, bank.charges);
            bank.rejectCharge = false;
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.recover(bank, inventory, false));
            assertEquals(1, bank.attempts); assertEquals(0, inventory.transfers);
            assertEquals(RodUpgradeJournal.Result.DONE, journal.cancelUnpaid(bank));
        }
    }
    @Test void backgroundRecoveryCompletesAlreadyCommittedPaymentButNeverChargesAgain() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var next = token(2);
        bank.failAfterCommit = true;
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, next));
            assertThrows(IOException.class, () -> journal.recover(bank, inventory, true));
            assertEquals(1, bank.charges); assertEquals(0, inventory.transfers);
        }
        try (var journal = new RodUpgradeJournal(file(), lineage, key, false)) {
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory, false));
            assertEquals(1, bank.charges); assertEquals(1, inventory.transfers);
            assertTrue(journal.current(next));
        }
    }

    @Test void cancelledIntentCannotChargeOrCreateAnIdentity() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); var next = token(2);
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, next));
            bank.unavailable = true;
            assertEquals(RodUpgradeJournal.Result.WAIT, journal.cancelUnpaid(bank));
            assertEquals(owner, journal.pendingOwner());
            bank.unavailable = false;
            assertEquals(RodUpgradeJournal.Result.DONE, journal.cancelUnpaid(bank));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory));
            assertFalse(journal.current(next)); assertEquals(0, bank.charges); assertEquals(0, inventory.transfers);
        }
    }

    @Test void paidButChangedInventoryRemainsPendingAndCannotBeCancelled() throws Exception {
        var bank = new Bank(); var inventory = new Inventory(); inventory.state = "changed";
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, token(2)));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.recover(bank, inventory));
            assertEquals(owner, journal.pendingOwner());
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertFalse(journal.current(token(2)));
            assertEquals(1, bank.charges); assertEquals(0, inventory.transfers);
        }
    }

    @Test void onlyLatestSignedGenerationCanBeUpgraded() throws Exception {
        var bank = new Bank(); var inventory = new Inventory();
        var level2 = token(2); var level3 = token(3);
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, level2));
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, prepare(journal, bank, null, level2));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, prepare(journal, bank, level2, token(4)));
            var forged = new RodToken.Token(rod, 3, level2.mac());
            assertThrows(IllegalArgumentException.class, () -> prepare(journal, bank, level2, forged));
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, level2, level3));
            inventory.state = "before";
            assertEquals(RodUpgradeJournal.Result.DONE, journal.recover(bank, inventory));
            assertFalse(journal.current(level2)); assertTrue(journal.current(level3));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, prepare(journal, bank, level2, level3));
            assertEquals(2, bank.charges); assertEquals(2, inventory.transfers);
        }
    }

    @Test void mismatchedBankCursorOrReceiptBlocksRecoveryAndBonuses() throws Exception {
        var bank = new Bank(); var inventory = new Inventory();
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) {
            assertEquals(RodUpgradeJournal.Result.WAIT, prepare(journal, bank, null, token(2)));
            bank.sequence = 2;
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.recover(bank, inventory));
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.cancelUnpaid(bank));
            assertFalse(journal.ready(bank)); assertEquals(0, bank.charges);
            bank.sequence = 1; // A cursor advance without the exact receipt is not evidence of payment.
            assertEquals(RodUpgradeJournal.Result.CONFLICT, journal.recover(bank, inventory));
            assertEquals(0, inventory.transfers);
        }
    }

    @Test void missingInvalidOrWrongLineageJournalIsNeverReinitialized() throws Exception {
        assertThrows(SQLException.class, () -> new RodUpgradeJournal(file(), lineage, key, false));
        try (var journal = new RodUpgradeJournal(file(), lineage, key, true)) { }
        assertThrows(SQLException.class, () -> new RodUpgradeJournal(file(), UUID.randomUUID(), key, false));
        Files.delete(file()); Files.writeString(file(), "not a database");
        assertThrows(SQLException.class, () -> new RodUpgradeJournal(file(), lineage, key, true));
        assertEquals("not a database", Files.readString(file()));
    }
}
