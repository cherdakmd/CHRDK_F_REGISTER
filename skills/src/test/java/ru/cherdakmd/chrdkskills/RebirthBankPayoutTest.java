package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RebirthBankPayoutTest {
    @TempDir Path folder;
    private static final String OWNER = "11111111-2222-3333-4444-555555555555";
    private final Map<String, Long> journal = new LinkedHashMap<>();
    private final RebirthBankPayout.Ledger ledger = new RebirthBankPayout.Ledger() {
        public Map<String, Long> reservations() { return Map.copyOf(journal); }
        public boolean accept(String key, long amount) { journal.putIfAbsent(key, amount); return true; }
    };

    private SkillStore fresh() {
        var store = new SkillStore(folder, Logger.getAnonymousLogger());
        store.load();
        store.addXp(OWNER, SkillType.MINING, 123);
        store.flush();
        return store;
    }

    private SkillStore reload() {
        var store = new SkillStore(folder, Logger.getAnonymousLogger());
        store.load();
        assertTrue(store.healthy());
        return store;
    }

    @Test void resetAndFrozenAmountRecoverAfterRestart() {
        var store = fresh();
        assertEquals(1, store.beginPaidRebirth(OWNER, 123));
        store = reload();
        assertEquals(1, store.rebirths(OWNER));
        assertEquals(0, store.xp(OWNER, SkillType.MINING));
        assertEquals(new SkillStore.PendingRebirth(1, 123), store.pendingRebirth(OWNER));
        assertTrue(RebirthBankPayout.reconcile(store, OWNER, ledger));
        assertEquals(Map.of("rebirth:1", 123L), journal);
        store = reload();
        assertNull(store.pendingRebirth(OWNER));
        assertEquals(new SkillStore.PendingRebirth(1, 123), store.lastBankRebirth(OWNER));
        assertTrue(RebirthBankPayout.reconcile(store, OWNER, ledger));
        assertEquals(1, journal.size());
    }

    @Test void restartAfterJournalReservationDoesNotDuplicate() {
        var store = fresh();
        assertEquals(1, store.beginPaidRebirth(OWNER, 123));
        journal.put("rebirth:1", 123L);
        assertTrue(RebirthBankPayout.reconcile(reload(), OWNER, ledger));
        assertEquals(Map.of("rebirth:1", 123L), journal);
        assertNull(reload().pendingRebirth(OWNER));
    }

    @Test void failedResetWriteRestoresInMemoryProgressAndNeverReserves() throws Exception {
        var store = fresh();
        Path tmp = folder.resolve("skills.json.tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("block"), "x");
        assertEquals(0, store.beginPaidRebirth(OWNER, 123));
        assertEquals(123, store.xp(OWNER, SkillType.MINING));
        assertEquals(0, store.rebirths(OWNER));
        assertNull(store.pendingRebirth(OWNER));
        assertTrue(journal.isEmpty());
        assertEquals(123, reload().xp(OWNER, SkillType.MINING));
        Files.delete(tmp.resolve("block"));
        Files.delete(tmp);
        assertEquals(1, store.beginPaidRebirth(OWNER, 123));
        assertTrue(RebirthBankPayout.reconcile(store, OWNER, ledger));
        assertEquals(123L, journal.get("rebirth:1"));
    }

    @Test void failureAfterReservationRecoversAfterRestart() throws Exception {
        var store = fresh();
        assertEquals(1, store.beginPaidRebirth(OWNER, 123));
        Path tmp = folder.resolve("skills.json.tmp");
        var blockedLedger = new RebirthBankPayout.Ledger() {
            public Map<String, Long> reservations() { return Map.copyOf(journal); }
            public boolean accept(String key, long amount) {
                journal.putIfAbsent(key, amount);
                try { Files.createDirectory(tmp); Files.writeString(tmp.resolve("block"), "x"); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
                return true;
            }
        };
        assertFalse(RebirthBankPayout.reconcile(store, OWNER, blockedLedger));
        assertEquals(Map.of("rebirth:1", 123L), journal);
        assertEquals(new SkillStore.PendingRebirth(1, 123), reload().pendingRebirth(OWNER));
        Files.delete(tmp.resolve("block"));
        Files.delete(tmp);
        assertTrue(RebirthBankPayout.reconcile(reload(), OWNER, ledger));
        assertEquals(Map.of("rebirth:1", 123L), journal);
        assertNull(reload().pendingRebirth(OWNER));
    }

    @Test void mismatchOrRolledBackSnapshotFailsClosed() {
        var store = fresh();
        assertEquals(1, store.beginPaidRebirth(OWNER, 123));
        journal.put("rebirth:1", 10L);
        assertFalse(RebirthBankPayout.reconcile(store, OWNER, ledger));
        assertEquals(new SkillStore.PendingRebirth(1, 123), reload().pendingRebirth(OWNER));
        journal.put("rebirth:1", 123L);
        assertTrue(RebirthBankPayout.reconcile(store, OWNER, ledger));
        journal.clear();
        assertFalse(RebirthBankPayout.reconcile(reload(), OWNER, ledger));
        journal.put("rebirth:1", 123L);
        journal.put("rebirth:2", 1L);
        assertFalse(RebirthBankPayout.reconcile(reload(), OWNER, ledger));
    }

    @Test void realJournalSurvivesRestartWithoutAnotherAward() throws Exception {
        var store = fresh();
        var id = java.util.UUID.fromString(OWNER);
        var source = new ru.cherdakmd.chrdkregister.api.MilestoneStore(folder.resolve("awards"), "skill-rebirth-diamonds");
        source.initialize(Map.of());
        assertEquals(1, store.beginPaidRebirth(OWNER, 288));
        assertTrue(source.reserve(id, "rebirth:1", 288));
        var reopened = new ru.cherdakmd.chrdkregister.api.MilestoneStore(folder.resolve("awards"), "skill-rebirth-diamonds");
        var actual = new RebirthBankPayout.Ledger() {
            public Map<String, Long> reservations() {
                var result = new LinkedHashMap<String, Long>();
                for (String key : reopened.claimed(id)) result.put(key, reopened.amount(id, key));
                return result;
            }
            public boolean accept(String key, long amount) {
                try { return reopened.reserve(id, key, amount); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            }
        };
        assertTrue(RebirthBankPayout.reconcile(reload(), OWNER, actual));
        assertEquals(288L, reopened.totals().get(id));
        assertTrue(RebirthBankPayout.reconcile(reload(), OWNER, actual));
        assertEquals(288L, reopened.totals().get(id));
    }

    @Test void calculatesFallbacksForOneOrTwoUniqueSlots() {
        assertEquals(32, RebirthRewardPlan.amount(32, 128, false, 1));
        assertEquals(96, RebirthRewardPlan.amount(32, 128, false, 0));
        assertEquals(160, RebirthRewardPlan.amount(32, 128, true, 2));
        assertEquals(224, RebirthRewardPlan.amount(32, 128, true, 1));
        assertEquals(288, RebirthRewardPlan.amount(32, 128, true, 0));
        assertEquals(0, RebirthRewardPlan.amount(-1, -1, true, 0));
        assertEquals(3328, RebirthRewardPlan.amount(Integer.MAX_VALUE, Integer.MAX_VALUE, true, 0));
    }
}
