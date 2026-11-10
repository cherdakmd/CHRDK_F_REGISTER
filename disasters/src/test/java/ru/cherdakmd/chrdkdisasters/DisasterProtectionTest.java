package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Геометрия площадной проверки приватов без сервера. */
final class DisasterProtectionTest {

    @Test
    void radiusAroundOriginTouchesFourChunks() {
        List<int[]> chunks = DisasterProtection.chunkKeysInRadius(0, 0, 15);
        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> c[0] == -1 && c[1] == -1));
        assertTrue(chunks.stream().anyMatch(c -> c[0] == 0 && c[1] == 0));
    }

    @Test
    void zeroRadiusIsExactlyOneChunk() {
        assertEquals(1, DisasterProtection.chunkKeysInRadius(7, 9, 0).size());
    }

    @Test
    void wideRadiusCoversEveryChunkItCrosses() {
        List<int[]> chunks = DisasterProtection.chunkKeysInRadius(100, 100, 32);
        assertEquals(25, chunks.size(), "5 x 5 чанков от (68..132)");
        assertTrue(chunks.stream().anyMatch(c -> c[0] == 4 && c[1] == 4));
        assertTrue(chunks.stream().anyMatch(c -> c[0] == 8 && c[1] == 8));
    }

    @Test
    void negativeCoordinatesDoNotCollapseIntoOneChunk() {
        assertEquals(4, DisasterProtection.chunkKeysInRadius(-1, -1, 15).size());
    }
}
