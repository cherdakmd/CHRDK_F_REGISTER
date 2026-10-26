package ru.cherdakmd.chrdkreferrals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище рефералки: привязки, выплаты, статистика, топ, persist round-trip.
 */
class ReferralStoreTest {

    private ReferralStore newStore(Path dir) {
        return new ReferralStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void linkCreateAndConsumeFlow(@TempDir Path dir) {
        ReferralStore store = newStore(dir);
        long now = 1_800_000_000_000L;
        store.createLink("Alex", "Steve", now);
        assertTrue(store.hasAnyLink("alex"), "регистр ника не важен");
        assertEquals(1, store.statsView("STEVE").linked);

        Optional<ReferralStore.RefLink> consumed = store.consumeLink("Alex", now + 1000);
        assertTrue(consumed.isPresent());
        assertEquals("Steve", consumed.get().inviter);
        assertTrue(consumed.get().consumed);
        assertTrue(store.consumeLink("Alex", now + 2000).isEmpty(), "бонус выплачивается один раз");
    }

    @Test
    void bonusStatsAccumulate(@TempDir Path dir) {
        ReferralStore store = newStore(dir);
        store.createLink("Alex", "Steve", 0);
        store.createLink("Bob", "Steve", 0);
        store.consumeLink("Alex", 1);
        store.recordBonusPaid("Steve", 7);
        ReferralStore.RefStats stats = store.statsView("steve");
        assertEquals(2, stats.linked);
        assertEquals(1, stats.paid);
        assertEquals(7, stats.hoursEarned);
    }

    @Test
    void topSortsByPaidThenHours(@TempDir Path dir) {
        ReferralStore store = newStore(dir);
        store.recordBonusPaid("Anna", 7);
        store.recordBonusPaid("Bob", 7);
        store.recordBonusPaid("Bob", 7);
        store.recordBonusPaid("Carl", 7);
        List<Map.Entry<String, ReferralStore.RefStats>> top = store.topByPaid(10);
        assertEquals("bob", top.get(0).getKey(), "больше оплативших — выше (Bob: 2)");
        assertEquals(1, top.get(1).getValue().paid, "Anna и Carl по 1");
        assertTrue(top.stream().anyMatch(e -> e.getKey().equals("anna")));
        assertTrue(top.size() <= 10, "топ ограничен 10 позициями");
    }

    @Test
    void resetRemovesOnlyLink(@TempDir Path dir) {
        ReferralStore store = newStore(dir);
        store.createLink("Alex", "Steve", 0);
        assertTrue(store.removeLink("ALEX"));
        assertFalse(store.removeLink("Alex"));
        assertFalse(store.hasAnyLink("Alex"));
        assertEquals(1, store.statsView("Steve").linked, "статистика пригласителя сохраняется");
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        ReferralStore store = newStore(dir);
        long now = 1_800_000_000_000L;
        store.createLink("Alex", "Steve", now);
        store.consumeLink("Alex", now + 1000);
        store.recordBonusPaid("Steve", 7);
        store.saveNow();

        ReferralStore reloaded = newStore(dir);
        reloaded.load();
        ReferralStore.RefLink link = reloaded.linkOf("alex").orElseThrow();
        assertEquals("Steve", link.inviter);
        assertTrue(link.consumed);
        assertEquals(now + 1000, link.paidAt);
        assertEquals(7, reloaded.statsView("steve").hoursEarned);
    }

    @Test
    void corruptedFileDoesNotCrashLoad(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("referrals-data.json"), "%%%");
        ReferralStore store = newStore(dir);
        store.load();
        assertFalse(store.hasAnyLink("anyone"));
    }
}
