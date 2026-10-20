package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimTeleportTest {

    @Test
    void centerIsEightBlocksFromChunkEdge() {
        assertEquals(8, ClaimTeleport.centerX(0));
        assertEquals(8, ClaimTeleport.centerZ(0));
        assertEquals(88, ClaimTeleport.centerX(5));
        assertEquals(-8, ClaimTeleport.centerX(-1), "чанк -1: блок -8, то есть точка -7.5");
        assertEquals(-24, ClaimTeleport.centerZ(-2));
    }

    @Test
    void radiusZeroChecksOnlyCenter() {
        List<ClaimTeleport.Column> columns = ClaimTeleport.searchOrder(0, 0, 0);
        assertEquals(List.of(new ClaimTeleport.Column(8, 8)), columns);
    }

    @Test
    void searchStartsAtCenterAndCoversRing() {
        List<ClaimTeleport.Column> columns = ClaimTeleport.searchOrder(0, 0, 1);
        assertEquals(9, columns.size());
        assertEquals(new ClaimTeleport.Column(8, 8), columns.get(0), "центр — первый кандидат");
        assertTrue(columns.contains(new ClaimTeleport.Column(7, 7)));
        assertTrue(columns.contains(new ClaimTeleport.Column(9, 8)));
        assertTrue(columns.contains(new ClaimTeleport.Column(8, 7)));
    }

    @Test
    void radiusIsClampedToAllowedRange() {
        assertEquals(1, ClaimTeleport.searchOrder(0, 0, -4).size());
        int max = 2 * ClaimTeleport.MAX_RADIUS + 1;
        assertEquals(max * max, ClaimTeleport.searchOrder(0, 0, 100).size());
        assertEquals(max * max, ClaimTeleport.searchOrder(0, 0, ClaimTeleport.MAX_RADIUS).size());
    }

    @Test
    void everyCandidateStaysInsideTheClaimChunk() {
        for (int chunk : new int[]{0, 1, -1, 7, -12}) {
            for (ClaimTeleport.Column column
                    : ClaimTeleport.searchOrder(chunk, chunk, ClaimTeleport.MAX_RADIUS)) {
                int minX = chunk * 16;
                assertTrue(column.x() >= minX && column.x() < minX + 16,
                        "X " + column.x() + " вышел за чанк " + chunk);
                assertTrue(column.z() >= minX && column.z() < minX + 16,
                        "Z " + column.z() + " вышел за чанк " + chunk);
            }
        }
    }
}
