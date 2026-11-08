package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Чистые JVM-тесты агрегации: без Bukkit, сети и графики. */
class WallDigestCollectorTest {

    private static final String DAY = "2026-11-03";

    @TempDir
    Path folder;

    private WallDigestStore store() {
        return new WallDigestStore(folder, Logger.getLogger("wall-digest-test"));
    }

    private WallDigestCollector fresh(WallDigestConfig config) {
        WallDigestStore store = store();
        WallDigestCollector collector = new WallDigestCollector(
                store, store.load(), name -> !name.equals("Hidden"), config);
        collector.rollDay(DAY); // как pollPrestige на живом сервере
        return collector;
    }

    @Test
    void newDayResetsCountersButKeepsPrestigeSnapshot() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.observePrestige("Steve", 3);
        collector.observePrestige("Steve", 5);
        collector.recordDonation("Alex");
        collector.recordOnline(14);
        collector.recordNewPlayer();

        WallDigestCollector.Digest first = collector.snapshot(DAY);
        assertEquals(2, first.prestigeEvents());
        assertEquals(1, first.donationEvents());
        assertEquals(14, first.peakOnline());
        assertEquals(1, first.newPlayers());
        assertEquals(4, first.total());

        WallDigestCollector.Digest next = collector.snapshot("2026-11-04");
        assertEquals(0, next.total());
        assertEquals(0, next.prestigeEvents());
        // Базовое значение пережило смену суток: повтор 5 не станет «приростом».
        collector.observePrestige("Steve", 5);
        assertEquals(0, collector.snapshot("2026-11-04").prestigeEvents());
        collector.observePrestige("Steve", 6);
        assertEquals(1, collector.snapshot("2026-11-04").prestigeEvents());
    }

    @Test
    void leadersAreRankedAndRespectOptOut() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT.withPlayerNames(true));
        collector.recordPrestige("Steve", 2);
        collector.recordPrestige("Alex", 7);
        collector.recordPrestige("Hidden", 99);
        collector.recordPrestige("Maria", 4);

        WallDigestCollector.Digest digest = collector.snapshot(DAY);
        assertEquals(List.of("Alex", "Maria", "Steve"), digest.leaders());
        // Opt-out скрыл имя, но не обезличенную цифру.
        assertEquals(112, digest.prestigeEvents());
    }

    @Test
    void namesAreHiddenByDefault() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.recordPrestige("Steve", 3);
        assertTrue(collector.snapshot(DAY).leaders().isEmpty());
    }

    @Test
    void postingIsOncePerDayAndStopsAfterThreeFailedAttempts() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.recordDonation("Alex");
        assertTrue(collector.shouldPost(DAY, 1));
        collector.markAttempt(DAY);
        collector.markPosted(DAY, 88L);
        assertFalse(collector.shouldPost(DAY, 1));

        collector.snapshot("2026-11-04"); // смена суток
        collector.recordDonation("Alex");
        assertTrue(collector.shouldPost("2026-11-04", 1));
        for (int i = 0; i < WallDigestCollector.MAX_ATTEMPTS; i++) {
            collector.markAttempt("2026-11-04");
        }
        assertFalse(collector.shouldPost("2026-11-04", 1));
    }

    @Test
    void emptyDayIsSuppressedByMinEvents() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        assertFalse(collector.shouldPost(DAY, 1));
        collector.recordOnline(9);
        assertFalse(collector.shouldPost(DAY, 1), "Онлайн сам по себе не повод для поста");
        collector.recordNewPlayer();
        assertTrue(collector.shouldPost(DAY, 1));
    }

    @Test
    void activityCountersAreAggregatedAndResetOnNewDay() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        assertFalse(collector.shouldPost(DAY, 1), "Пустые сутки не публикуются");
        collector.recordBlocksBroken(12);
        collector.recordDiamonds(5);
        collector.recordDiamonds(2);
        collector.recordMobKills(3);

        WallDigestCollector.Digest today = collector.snapshot(DAY);
        assertEquals(12, today.blocksBroken());
        assertEquals(7, today.diamondsGained());
        assertEquals(3, today.mobKills());
        assertEquals(22L, today.total());
        assertTrue(collector.shouldPost(DAY, 1), "Игровая активность тоже будит пост");

        WallDigestCollector.Digest tomorrow = collector.snapshot("2026-11-04");
        assertEquals(0, tomorrow.blocksBroken());
        assertEquals(0, tomorrow.diamondsGained());
        assertEquals(0, tomorrow.mobKills());
        assertEquals(0L, tomorrow.total());
    }

    @Test
    void negativeOrZeroActivityIsIgnoredAndCountersNeverOverflow() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.recordBlocksBroken(-1);
        collector.recordDiamonds(0);
        collector.recordMobKills(-5);
        assertEquals(0L, collector.snapshot(DAY).total());
        collector.recordBlocksBroken(Long.MAX_VALUE);
        collector.recordBlocksBroken(10);
        assertEquals(Long.MAX_VALUE, collector.snapshot(DAY).blocksBroken());
    }

    @Test
    void perPlayerActivityFeedsDailyNominations() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.recordBlocksBroken("Alex", 5);
        collector.recordBlocksBroken("Steve", 9);
        collector.recordDiamonds("Alex", 12);
        collector.recordDiamonds("Steve", 3);
        collector.recordMobKills("Maria", 4);
        collector.recordMobKills("Alex", 2);
        collector.observePrestige("Maria", 7);
        collector.observePrestige("Alex", 3);

        assertEquals("Steve", collector.topBlocks().name());
        assertEquals(9L, collector.topBlocks().value());
        assertEquals("Alex", collector.topDiamonds().name());
        assertEquals(12L, collector.topDiamonds().value());
        assertEquals("Maria", collector.topMobKills().name());
        assertEquals(4L, collector.topMobKills().value());
        assertEquals(3L, collector.diamondsOf("Steve"));
        assertEquals(7L, collector.topPrestige().value());
        assertEquals("Maria", collector.topPrestige().name());

        // Суммы в дайджесте остаются обезличенными.
        WallDigestCollector.Digest digest = collector.snapshot(DAY);
        assertEquals(14, digest.blocksBroken());
        assertEquals(15, digest.diamondsGained());
        assertEquals(6, digest.mobKills());
    }

    @Test
    void perPlayerTopsAreResetOnNewDay() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.recordBlocksBroken("Steve", 4);
        collector.recordMobKills("Steve", 2);
        assertEquals("Steve", collector.topBlocks().name());
        collector.snapshot("2026-11-04");
        assertNull(collector.topBlocks());
        assertNull(collector.topMobKills());
        assertNull(collector.topDiamonds());
        assertEquals(0L, collector.diamondsOf("Steve"));
    }

    @Test
    void unresolvedPostingIsReportedOnce() {
        WallDigestCollector collector = fresh(WallDigestConfig.DEFAULT);
        collector.markPosting(DAY);
        assertTrue(collector.hasPending(DAY));
        assertEquals(DAY, collector.takeUnresolvedPosting());
        assertEquals("", collector.takeUnresolvedPosting());
        assertFalse(collector.hasPending(DAY));
    }

    @Test
    void stateSurvivesReloadFromDisk() {
        WallDigestStore store = store();
        WallDigestCollector first = new WallDigestCollector(
                store, store.load(), name -> true, WallDigestConfig.DEFAULT);
        first.rollDay(DAY);
        first.recordDonation("Alex");
        first.markPosted("2026-11-02", 77L);
        first.save();

        WallDigestCollector second = new WallDigestCollector(
                store, store.load(), name -> true, WallDigestConfig.DEFAULT);
        assertEquals(1, second.snapshot(DAY).donationEvents(), "Счётчики пережили рестарт");
        assertFalse(second.shouldPost("2026-11-02", 1), "Дедуп пережил рестарт");
    }
}
