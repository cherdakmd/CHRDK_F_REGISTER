package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AlbumRewardJournalTest {
    @TempDir Path dir;
    static final class Bank implements AlbumRewardJournal.Bank {
        final Map<UUID, Long> values = new HashMap<>();
        boolean failAfterWrite;
        public Map<UUID, Long> receipts(UUID lineage) { return Map.copyOf(values); }
        public boolean credit(UUID lineage, UUID owner, long total) throws IOException {
            values.merge(owner, total, Math::max);
            if (failAfterWrite) { failAfterWrite = false; throw new IOException("ambiguous bank write"); }
            return true;
        }
    }
    @Test void freshJournalReconstructsKnownBankReceiptsWithoutPayingTwice() throws Exception {
        var path = dir.resolve("new-rewards.db"); var lineage = UUID.randomUUID(); var owner = UUID.randomUUID();
        var other = UUID.randomUUID(); var bank = new Bank(); bank.values.put(owner, 350L); bank.values.put(other, 50L);
        try (var journal = new AlbumRewardJournal(path, lineage, true)) {
            journal.bootstrapFromBank(bank);
            assertEquals(new AlbumRewardJournal.Progress(3, 3, 350, 350), journal.progress(owner));
            assertEquals(new AlbumRewardJournal.Progress(1, 1, 50, 50), journal.progress(other));
            assertFalse(journal.hasUnsettled()); assertTrue(journal.reconciled(bank));
            assertEquals(AlbumRewardJournal.Result.DONE, journal.claim(owner, 20, bank));
            assertEquals(350L, bank.values.get(owner));
            assertThrows(Exception.class, () -> journal.bootstrapFromBank(bank));
        }
        try (var journal = new AlbumRewardJournal(path, lineage, false)) {
            assertTrue(journal.reconciled(bank));
            assertEquals(AlbumRewardJournal.Result.DONE, journal.claim(owner, 30, bank));
            assertEquals(850L, bank.values.get(owner));
        }
    }
    @Test void unexpectedBankAmountsAndUnavailableSnapshotNeverBootstrap() throws Exception {
        var path = dir.resolve("new-rewards.db"); var lineage = UUID.randomUUID(); var owner = UUID.randomUUID(); var bank = new Bank();
        bank.values.put(owner, 123L);
        try (var journal = new AlbumRewardJournal(path, lineage, true)) {
            assertThrows(Exception.class, () -> journal.bootstrapFromBank(bank));
            assertEquals(new AlbumRewardJournal.Progress(0, 0, 0, 0), journal.progress(owner));
            assertFalse(journal.reconciled(bank));
            AlbumRewardJournal.Bank unavailable = new AlbumRewardJournal.Bank() {
                public Map<UUID, Long> receipts(UUID ignored) { return null; }
                public boolean credit(UUID l, UUID o, long total) { throw new AssertionError("must not pay"); }
            };
            assertThrows(Exception.class, () -> journal.bootstrapFromBank(unavailable));
        }
    }
    @Test void interruptedCreditRecoversWithoutPayingTwice() throws Exception {
        var path = dir.resolve("rewards.db"); var lineage = UUID.randomUUID(); var owner = UUID.randomUUID(); var bank = new Bank();
        bank.failAfterWrite = true;
        try (var journal = new AlbumRewardJournal(path, lineage, true)) {
            assertThrows(IOException.class, () -> journal.claim(owner, 10, bank));
            assertEquals(150, bank.values.get(owner));
            assertEquals(0, journal.progress(owner).paidStage());
            assertTrue(journal.hasUnsettled());
        }
        try (var journal = new AlbumRewardJournal(path, lineage, false)) {
            assertEquals(AlbumRewardJournal.Result.DONE, journal.recover(owner, bank));
            assertEquals(2, journal.progress(owner).paidStage());
            assertFalse(journal.hasUnsettled());
            assertEquals(AlbumRewardJournal.Result.DONE, journal.claim(owner, 10, bank));
            assertEquals(150, bank.values.get(owner));
            // An empty/new collection must not replay rewards already issued to this owner.
            assertEquals(AlbumRewardJournal.Result.DONE, journal.claim(owner, 5, bank));
            assertEquals(150, bank.values.get(owner));
            assertEquals(AlbumRewardJournal.Result.DONE, journal.claim(owner, 30, bank));
            assertEquals(850, bank.values.get(owner));
            bank.values.put(UUID.randomUUID(), 50L);
            assertEquals(AlbumRewardJournal.Result.CONFLICT, journal.claim(owner, 30, bank));
        }
    }
}
