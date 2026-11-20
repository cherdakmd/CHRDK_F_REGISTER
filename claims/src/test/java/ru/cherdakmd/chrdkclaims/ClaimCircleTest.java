package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaimCircleTest {
    private static ClaimCircle.Cell cell(int x, int z) { return new ClaimCircle.Cell(x, z); }

    @Test void radiusOneIsDiskNotSquare() {
        assertEquals(List.of(cell(-1, 0), cell(0, -1), cell(0, 0), cell(0, 1), cell(1, 0)),
                ClaimCircle.expand(List.of(cell(0, 0)), 1, 100, 100));
    }

    @Test void territoryUsesUnionWithoutDuplicatesAndIsDeterministic() {
        var result = ClaimCircle.expand(List.of(cell(1, 0), cell(0, 0)), 1, 100, 100);
        assertEquals(8, result.size());
        assertEquals(result, ClaimCircle.expand(List.of(cell(0, 0), cell(1, 0)), 1, 100, 100));
        assertTrue(result.contains(cell(-1, 0)));
        assertTrue(result.contains(cell(2, 0)));
        assertFalse(result.contains(cell(-1, -1)));
    }

    @Test void boundsAndLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(Integer.MAX_VALUE, 0)), 1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, Integer.MIN_VALUE)), 1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 0, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 1, 8, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 1, 100, 4));
    }
}
