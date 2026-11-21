package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaimCircleTest {
    private static ClaimCircle.Cell cell(int x, int z) { return new ClaimCircle.Cell(x, z); }

    @Test void radiusOneIncludesAllEightNeighbours() {
        var result = ClaimCircle.expand(List.of(cell(0, 0)), 1, 100, 100);
        assertEquals(9, result.size());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) assertTrue(result.contains(cell(x, z)));
    }

    @Test void territoryUsesUnionWithoutDuplicatesAndIsDeterministic() {
        var result = ClaimCircle.expand(List.of(cell(1, 0), cell(0, 0)), 1, 100, 100);
        assertEquals(12, result.size());
        assertEquals(result, ClaimCircle.expand(List.of(cell(0, 0), cell(1, 0)), 1, 100, 100));
        assertTrue(result.contains(cell(-1, -1)));
        assertTrue(result.contains(cell(2, 1)));
    }

    @Test void radiusTwoAndNegativeCoordinatesIncludeCorners() {
        var result = ClaimCircle.expand(List.of(cell(-20, -30)), 2, 100, 100);
        assertEquals(25, result.size());
        assertTrue(result.contains(cell(-22, -32)));
        assertTrue(result.contains(cell(-18, -28)));
        assertFalse(result.contains(cell(-23, -30)));
        assertEquals(121, ClaimCircle.expand(List.of(cell(0, 0)), 5, 200, 256).size());
    }

    @Test void refusedConfirmationInvalidatesOnlyThatPlayersQuote() {
        var confirmations = new ru.cherdakmd.chrdkregister.api.EconomyConfirmations();
        var owner = java.util.UUID.randomUUID(); var other = java.util.UUID.randomUUID();
        var session = new Object(); var otherSession = new Object();
        assertTrue(confirmations.offer(owner, session, "circle", 0));
        assertTrue(confirmations.offer(other, otherSession, "other", 0));
        ClaimsService.discardCircleConfirmation(confirmations, owner, session, 1);
        assertFalse(confirmations.consume(owner, session, "circle", 2));
        assertTrue(confirmations.consume(other, otherSession, "other", 2));
    }

    @Test void boundsAndLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(Integer.MAX_VALUE, 0)), 1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, Integer.MIN_VALUE)), 1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 0, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 1, 8, 100));
        assertThrows(IllegalArgumentException.class, () -> ClaimCircle.expand(List.of(cell(0, 0)), 1, 100, 4));
    }
}
