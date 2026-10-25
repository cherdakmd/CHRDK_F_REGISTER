package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звания: пороги, переходы и прогресс должны быть монотонными и без дыр —
 * иначе игрок «перескочит» ранг или застрянет на нём навсегда.
 */
class TreasureRankTest {

    @Test
    void thresholdsCoverEveryBoundary() {
        assertEquals(TreasureRank.NOVICE, TreasureRank.of(0));
        assertEquals(TreasureRank.NOVICE, TreasureRank.of(4));
        assertEquals(TreasureRank.SEEKER, TreasureRank.of(5));
        assertEquals(TreasureRank.SEEKER, TreasureRank.of(14));
        assertEquals(TreasureRank.TREASURE_HUNTER, TreasureRank.of(15));
        assertEquals(TreasureRank.CONNOISSEUR, TreasureRank.of(35));
        assertEquals(TreasureRank.MASTER, TreasureRank.of(75));
        assertEquals(TreasureRank.MASTER, TreasureRank.of(149));
        assertEquals(TreasureRank.LEGEND, TreasureRank.of(150));
        assertEquals(TreasureRank.LEGEND, TreasureRank.of(100_000));
    }

    @Test
    void thresholdsAreStrictlyIncreasing() {
        long previous = -1L;
        for (TreasureRank rank : TreasureRank.values()) {
            assertTrue(rank.minFound() > previous,
                    "порог " + rank.id() + " не больше предыдущего");
            previous = rank.minFound();
        }
        assertEquals(0L, TreasureRank.NOVICE.minFound());
    }

    @Test
    void nextChainEndsAtLegend() {
        TreasureRank rank = TreasureRank.NOVICE;
        for (TreasureRank expected : new TreasureRank[]{
                TreasureRank.SEEKER, TreasureRank.TREASURE_HUNTER,
                TreasureRank.CONNOISSEUR, TreasureRank.MASTER, TreasureRank.LEGEND}) {
            rank = rank.next();
            assertEquals(expected, rank);
        }
        assertNull(TreasureRank.LEGEND.next());
    }

    @Test
    void remainingCountsDownToNextRank() {
        assertEquals(5L, TreasureRank.NOVICE.remaining(0));
        assertEquals(2L, TreasureRank.NOVICE.remaining(3));
        assertEquals(0L, TreasureRank.NOVICE.remaining(5));
        assertEquals(50L, TreasureRank.MASTER.remaining(100));
        assertEquals(0L, TreasureRank.LEGEND.remaining(1000));
    }

    @Test
    void progressIsClampedBetweenZeroAndOne() {
        assertEquals(0.0, TreasureRank.NOVICE.progress(0), 1e-9);
        assertEquals(0.4, TreasureRank.NOVICE.progress(2), 1e-9);
        assertEquals(1.0, TreasureRank.NOVICE.progress(5), 1e-9);
        assertEquals(0.0, TreasureRank.MASTER.progress(0), 1e-9);
        assertEquals(0.5, TreasureRank.SEEKER.progress(10), 1e-9);
        assertEquals(1.0, TreasureRank.LEGEND.progress(150), 1e-9);
        assertEquals(1.0, TreasureRank.LEGEND.progress(9999), 1e-9);
    }

    @Test
    void idsAreUniqueAndParsedBothWays() {
        Set<String> ids = new HashSet<>();
        for (TreasureRank rank : TreasureRank.values()) {
            assertTrue(ids.add(rank.id()), "дубль ида звания: " + rank.id());
            assertEquals(rank, TreasureRank.byId(rank.id()));
            assertEquals(rank, TreasureRank.byId(rank.id().replace('_', '-')));
            assertEquals(rank, TreasureRank.byId(rank.name().toLowerCase()));
            assertFalse(rank.id().isBlank());
        }
        assertNull(TreasureRank.byId(null));
        assertNull(TreasureRank.byId(""));
        assertNull(TreasureRank.byId("император"));
    }

    @Test
    void rewardsGrowWithRankAndStartFree() {
        int previous = -1;
        for (TreasureRank rank : TreasureRank.values()) {
            assertTrue(rank.diamonds() >= previous, "награда падает у " + rank.id());
            previous = rank.diamonds();
        }
        assertEquals(0, TreasureRank.NOVICE.diamonds());
        assertTrue(TreasureRank.LEGEND.diamonds() > TreasureRank.MASTER.diamonds());
    }
}
