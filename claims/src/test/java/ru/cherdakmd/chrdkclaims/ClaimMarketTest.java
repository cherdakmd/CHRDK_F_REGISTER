package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMarketTest {

    private static ClaimMarket.Offer offer(String world, int x, int z) {
        return new ClaimMarket.Offer(world, x, z, "Steve", 16);
    }

    @Test
    void ownWorldComesFirstAndNearestFirst() {
        List<ClaimMarket.Offer> sorted = ClaimMarket.sorted(List.of(
                offer("world_nether", 0, 0),
                offer("world", 10, 0),
                offer("world", 2, 0),
                offer("world", 0, 2)), "world", 0, 0);
        assertEquals(List.of(
                offer("world", 0, 2),
                offer("world", 2, 0),
                offer("world", 10, 0),
                offer("world_nether", 0, 0)), sorted);
    }

    @Test
    void distanceIsMeasuredInChunks() {
        assertEquals(5, ClaimMarket.distance(offer("world", 3, 4), "world", 0, 0));
        assertEquals(0, ClaimMarket.distance(offer("world", 7, 7), "world", 7, 7));
        assertEquals(Integer.MAX_VALUE,
                ClaimMarket.distance(offer("world", 0, 0), "world_nether", 0, 0),
                "чужой мир не сравнивается по расстоянию");
    }

    @Test
    void otherWorldsAreSortedByCoordinates() {
        List<ClaimMarket.Offer> sorted = ClaimMarket.sorted(List.of(
                offer("world_the_end", 5, 5),
                offer("world_nether", 1, 2),
                offer("world_nether", 1, 1)), "world", 0, 0);
        assertEquals(List.of(
                offer("world_nether", 1, 1),
                offer("world_nether", 1, 2),
                offer("world_the_end", 5, 5)), sorted);
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertTrue(ClaimMarket.sorted(List.of(), "world", 0, 0).isEmpty());
        assertEquals(Integer.MAX_VALUE, ClaimMarket.distance(offer("world", 0, 0), null, 0, 0));
        assertEquals(Integer.MAX_VALUE, ClaimMarket.distance(null, "world", 0, 0));
        assertEquals(1, ClaimMarket.sorted(List.of(offer("world", 0, 0)), null, 0, 0).size());
    }
}
