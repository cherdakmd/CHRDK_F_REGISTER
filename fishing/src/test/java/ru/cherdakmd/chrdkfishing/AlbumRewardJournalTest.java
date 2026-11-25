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
