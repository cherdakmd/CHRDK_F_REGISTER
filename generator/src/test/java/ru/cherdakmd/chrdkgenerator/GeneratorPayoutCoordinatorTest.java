package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.MilestoneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorPayoutCoordinatorTest {
    @TempDir Path dir;
    private final UUID owner = UUID.randomUUID();

    private GeneratorStore fresh() throws Exception {
        var store = new GeneratorStore(dir, Logger.getAnonymousLogger());
        store.load();
        store.markRegistered();
        store.install(owner, UUID.randomUUID(), "world", 0, 64, 0, 1, 1);
        store.flush();
        return store;
    }

    private GeneratorStore reload() {
        var store = new GeneratorStore(dir, Logger.getAnonymousLogger());
        store.load();
        assertTrue(store.healthy());
        return store;
    }

    private GeneratorPayoutCoordinator.Ledger ledger(MilestoneStore journal) {
        return new GeneratorPayoutCoordinator.Ledger() {
            public Map<String, Long> reservations(UUID id) {
                var result = new java.util.LinkedHashMap<String, Long>();
                for (String key : journal.claimed(id)) result.put(key, journal.amount(id, key));
                return result;
            }
            public boolean accept(String key, long amount) {
                try { return journal.reserve(owner, key, amount); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            }
        };
    }

    private MilestoneStore journal() throws Exception {
        var journal = new MilestoneStore(dir.resolve("awards"), "generator-payouts");
        journal.initialize(Map.of());
        return journal;
    }

    @Test void drainsLargeBalanceInIdempotentTechnicalBatches() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 2_000_005);
        for (int i = 0; i < 3; i++) assertTrue(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(0, reload().payoutState(owner).money());
        assertEquals(2_000_005, journal.totals().get(owner));
        assertEquals(3, reload().payoutState(owner).sequence());
        assertTrue(GeneratorPayoutCoordinator.run(reload(), owner, ledger(journal)));
        assertEquals(2_000_005, journal.totals().get(owner));
    }

    @Test void restartAfterReservationUsesFrozenPendingAmount() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 123);
        assertTrue(store.finishLegacyReconciliation(owner));
        assertTrue(store.preparePayout(owner, 123));
        assertTrue(journal.reserve(owner, GeneratorPayoutCoordinator.key(owner, 0), 123));
        store = reload();
        store.addMoney(owner, 77);
        assertTrue(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(77, reload().payoutState(owner).money());
        assertEquals(123, journal.totals().get(owner));
        assertTrue(GeneratorPayoutCoordinator.run(reload(), owner, ledger(journal)));
        assertEquals(200, journal.totals().get(owner));
        assertEquals(0, reload().payoutState(owner).money());
    }

    @Test void legacyAwardUsesJournalAmountNotCurrentBalance() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 300);
        journal.reserve(owner, GeneratorPayouts.eventKey(owner, 20_000), 100);
        assertTrue(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(300, journal.totals().get(owner));
        assertEquals(200, journal.amount(owner, GeneratorPayoutCoordinator.key(owner, 0)));
        assertEquals(0, reload().payoutState(owner).money());
        assertEquals(20_000, reload().payoutState(owner).legacyDay());
    }

    @Test void uncertainLegacyStateStopsInsteadOfMinting() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 20);
        journal.reserve(owner, GeneratorPayouts.eventKey(owner, 20_000), 100);
        assertFalse(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(20, reload().payoutState(owner).money());
        assertEquals(100, journal.totals().get(owner));
        assertFalse(reload().payoutState(owner).legacyReconciled());
    }

    @Test void wrongExistingV2AmountStopsWithoutDeducting() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 100);
        assertTrue(store.finishLegacyReconciliation(owner));
        assertTrue(store.preparePayout(owner, 100));
        journal.reserve(owner, GeneratorPayoutCoordinator.key(owner, 0), 50);
        assertFalse(GeneratorPayoutCoordinator.run(reload(), owner, ledger(journal)));
        assertEquals(100, reload().payoutState(owner).money());
        assertEquals(100, reload().payoutState(owner).pending());
        assertEquals(50, journal.totals().get(owner));
    }

    @Test void olderOrLostGeneratorStateNeverReusesPaidSequence() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 50);
        assertTrue(store.finishLegacyReconciliation(owner));
        journal.reserve(owner, GeneratorPayoutCoordinator.key(owner, 0), 50);
        // There is no matching pending intent in the restored generator snapshot.
        assertFalse(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(50, journal.totals().get(owner));
        assertEquals(50, reload().payoutState(owner).money());
        assertEquals(0, reload().payoutState(owner).pending());
    }

    @Test void lostGeneratorMigrationMarkerWithV2JournalFailsClosed() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 50);
        journal.reserve(owner, GeneratorPayoutCoordinator.key(owner, 0), 50);
        assertFalse(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertFalse(reload().payoutState(owner).legacyReconciled());
        assertEquals(50, reload().payoutState(owner).money());
    }

    @Test void failedGeneratorCheckpointNeverReservesMoney() throws Exception {
        var store = fresh();
        var journal = journal();
        store.addMoney(owner, 50);
        Path tmp = dir.resolve("generator.json.tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("block"), "x");
        assertFalse(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertTrue(journal.totals().isEmpty());
        assertEquals(0, reload().payoutState(owner).money());
        Files.delete(tmp.resolve("block"));
        Files.delete(tmp);
        assertTrue(GeneratorPayoutCoordinator.run(store, owner, ledger(journal)));
        assertEquals(50, journal.totals().get(owner));
        assertEquals(0, reload().payoutState(owner).money());
    }
}
