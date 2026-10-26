package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionRewardsTest {

    @Test
    void percentRoundsDownAndNeverExceedsHundred() {
        assertEquals(0, CollectionRewards.percentOf(0, 65));
        assertEquals(0, CollectionRewards.percentOf(0, 0));
        assertEquals(30, CollectionRewards.percentOf(20, 65));
        assertEquals(100, CollectionRewards.percentOf(65, 65));
        assertEquals(100, CollectionRewards.percentOf(999, 65));
        assertEquals(0, CollectionRewards.percentOf(-5, 65));
    }

    @Test
    void tiersAreSortedAndDeduplicated() {
        List<CollectionRewards.Tier> normalized = CollectionRewards.normalize(List.of(
                new CollectionRewards.Tier(75, 3, 24),
                new CollectionRewards.Tier(25, 1, 8),
                new CollectionRewards.Tier(75, 9, 99)));
        assertEquals(2, normalized.size());
        assertEquals(25, normalized.get(0).percent());
        assertEquals(75, normalized.get(1).percent());
        // При дубле побеждает первая по порядку запись, а не «богатая» вторая.
        assertEquals(3, normalized.get(1).passHours());
    }

    @Test
    void tierValuesAreClamped() {
        CollectionRewards.Tier tier = new CollectionRewards.Tier(500, -3, 10_000);
        assertEquals(100, tier.percent());
        assertEquals(0, tier.passHours());
        assertEquals(576, tier.diamonds());
        assertTrue(tier.empty() == false);
    }

    @Test
    void claimableSkipsClaimedAndFutureTiers() {
        List<CollectionRewards.Tier> tiers = CollectionRewards.defaults();
        // 20 из 65 — это 30 %: пройден только порог 25.
        List<CollectionRewards.Tier> first = CollectionRewards.claimable(tiers, Set.of(), 20, 65);
        assertEquals(1, first.size());
        assertEquals(25, first.get(0).percent());

        // Порог 25 уже выдан — следующий 50 (33 из 65 = 50 %).
        List<CollectionRewards.Tier> second = CollectionRewards.claimable(tiers, Set.of(25), 33, 65);
        assertEquals(1, second.size());
        assertEquals(50, second.get(0).percent());

        // Ничего не открыто — наград нет.
        assertTrue(CollectionRewards.claimable(tiers, Set.of(), 0, 65).isEmpty());
    }

    @Test
    void emptyTierIsStillClaimableForTheMilestone() {
        List<CollectionRewards.Tier> tiers = List.of(new CollectionRewards.Tier(10, 0, 0));
        List<CollectionRewards.Tier> claimable = CollectionRewards.claimable(tiers, Set.of(), 7, 65);
        assertEquals(1, claimable.size());
        assertTrue(claimable.get(0).empty());
    }

    @Test
    void barHasFixedWidthAndFillsFromLeft() {
        assertEquals("░░░░░░░░░░", CollectionRewards.bar(0, 10));
        assertEquals("██████░░░░", CollectionRewards.bar(60, 10));
        assertEquals("██████████", CollectionRewards.bar(100, 10));
        assertEquals(10, CollectionRewards.bar(150, 10).length());
        assertEquals(5, CollectionRewards.bar(0, 5).length());
    }
}
