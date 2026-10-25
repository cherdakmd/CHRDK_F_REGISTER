package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMenuModelTest {

    private static Claim claim(String world, int x, int z, String owner, int salePrice) {
        Claim claim = new Claim(world, x, z, owner, 1_000L);
        claim.salePrice = salePrice;
        return claim;
    }

    @Test
    void mineTabKeepsOnlyOwnClaims() {
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.view(List.of(
                claim("world", 0, 0, "Steve", 0),
                claim("world", 1, 0, "Alex", 0),
                claim("world", 2, 0, "Steve", 32)), "steve", ClaimMenuModel.Tab.MINE);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(ClaimMenuModel.Entry::mine));
        assertEquals(List.of(0, 32), entries.stream().map(ClaimMenuModel.Entry::salePrice).toList());
    }

    @Test
    void saleTabKeepsOnlyOffersIncludingOwn() {
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.view(List.of(
                claim("world", 0, 0, "Steve", 0),
                claim("world", 1, 0, "Alex", 64),
                claim("world", 2, 0, "Steve", 16)), "Steve", ClaimMenuModel.Tab.FOR_SALE);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.salePrice() > 0));
        assertEquals(1, entries.stream().filter(ClaimMenuModel.Entry::mine).count());
    }

    @Test
    void brokenClaimsAreSkipped() {
        Claim noWorld = new Claim(" ", 0, 0, "Steve", 1_000L);
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.view(
                java.util.Arrays.asList(null, noWorld, claim("world", 0, 0, "Steve", 0)),
                "Steve", ClaimMenuModel.Tab.MINE);
        assertEquals(1, entries.size());
        assertEquals("world", entries.get(0).world());
    }

    @Test
    void sortingPutsOwnWorldAndNearestFirst() {
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.sorted(ClaimMenuModel.view(List.of(
                claim("world_nether", 0, 0, "Steve", 0),
                claim("world", 10, 0, "Steve", 0),
                claim("world", 2, 0, "Steve", 0),
                claim("world", 0, 1, "Steve", 0)), "Steve", ClaimMenuModel.Tab.MINE),
                "world", 0, 0);
        assertEquals(List.of("world:0:1", "world:2:0", "world:10:0", "world_nether:0:0"),
                entries.stream()
                        .map(entry -> entry.world() + ':' + entry.x() + ':' + entry.z())
                        .toList());
    }

    @Test
    void otherWorldsAreOrderedByCoordinates() {
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.sorted(List.of(
                new ClaimMenuModel.Entry("world_the_end", 5, 5, "Alex", 0L, 16, false),
                new ClaimMenuModel.Entry("world_nether", 1, 2, "Alex", 0L, 16, false),
                new ClaimMenuModel.Entry("world_nether", 1, 1, "Alex", 0L, 16, false)),
                "world", 0, 0);
        assertEquals(List.of("world_nether:1:1", "world_nether:1:2", "world_the_end:5:5"),
                entries.stream()
                        .map(entry -> entry.world() + ':' + entry.x() + ':' + entry.z())
                        .toList());
    }

    @Test
    void distanceIsSquaredChunkDistance() {
        ClaimMenuModel.Entry entry = new ClaimMenuModel.Entry("world", 3, 4, "Steve", 0L, 0, true);
        assertEquals(25, ClaimMenuModel.distance(entry, "world", 0, 0));
        assertEquals(0, ClaimMenuModel.distance(entry, "world", 3, 4));
        assertEquals(Long.MAX_VALUE, ClaimMenuModel.distance(entry, "world_nether", 3, 4));
    }

    @Test
    void menuPagesHoldTwentyEightCards() {
        assertEquals(1, Pagination.pageCount(0, ClaimMenuModel.PAGE_SIZE));
        assertEquals(1, Pagination.pageCount(28, ClaimMenuModel.PAGE_SIZE));
        assertEquals(2, Pagination.pageCount(29, ClaimMenuModel.PAGE_SIZE));
        assertEquals(28, Pagination.slice(cards(56), 1, ClaimMenuModel.PAGE_SIZE).size());
        assertEquals(28, Pagination.slice(cards(56), 3, ClaimMenuModel.PAGE_SIZE).size(),
                "третья страница при двух возможных зажимается во вторую");
    }

    @Test
    void tabSwitchesToTheOppositeOne() {
        assertEquals(ClaimMenuModel.Tab.FOR_SALE, ClaimMenuModel.Tab.MINE.other());
        assertEquals(ClaimMenuModel.Tab.MINE, ClaimMenuModel.Tab.FOR_SALE.other());
        assertEquals("menu-tab-mine", ClaimMenuModel.titleKey(ClaimMenuModel.Tab.MINE));
        assertEquals("menu-tab-sale", ClaimMenuModel.titleKey(ClaimMenuModel.Tab.FOR_SALE));
    }

    private static List<ClaimMenuModel.Entry> cards(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new ClaimMenuModel.Entry("world", index, 0, "Steve", 0L, 0, true))
                .toList();
    }
}
