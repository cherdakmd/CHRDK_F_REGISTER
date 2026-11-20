package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.EconomyConfirmations;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClaimCircleCommandTest {
    @Test void parsesOnlyDocumentedForms() {
        assertEquals(new ClaimCircle.Command(1, false, false), ClaimCircle.parse(new String[]{"circle", "1"}));
        assertEquals(new ClaimCircle.Command(5, false, true), ClaimCircle.parse(new String[]{"CIRCLE", "5", "CONFIRM"}));
        assertEquals(new ClaimCircle.Command(2, true, false), ClaimCircle.parse(new String[]{"circle", "2", "territory"}));
        assertEquals(new ClaimCircle.Command(2, true, true), ClaimCircle.parse(new String[]{"circle", "2", "TERRITORY", "CONFIRM"}));
        for (String[] args : List.of(
                new String[]{"circle"}, new String[]{"circle", "0"}, new String[]{"circle", "6"},
                new String[]{"circle", "abc"}, new String[]{"circle", "99999999999999"},
                new String[]{"circle", "2", "all"}, new String[]{"circle", "2", "confirm", "territory"},
                new String[]{"circle", "2", "territory", "no"},
                new String[]{"circle", "2", "territory", "confirm", "extra"})) {
            assertThrows(IllegalArgumentException.class, () -> ClaimCircle.parse(args));
        }
    }

    private ClaimsService.CircleQuote quote(String world, int x, int z, int radius, boolean territory,
                                             List<ClaimCircle.Cell> area, List<ClaimCircle.Cell> free,
                                             int price, int upkeep, int owned, int limit) {
        return new ClaimsService.CircleQuote(world, x, z, radius, territory, area, free, price, upkeep, owned, limit);
    }

    @Test void consentBindsCircleCoordinatesAreaRatesLimitsModeAndSession() {
        var c = new EconomyConfirmations(); var owner = UUID.randomUUID(); var session = new Object();
        var area = List.of(new ClaimCircle.Cell(1, 2), new ClaimCircle.Cell(1, 3));
        var free = List.of(new ClaimCircle.Cell(1, 3));
        var original = quote("world", 1, 2, 1, false, area, free, 20, 5, 1, 10);
        var changes = List.of(
                quote("other", 1, 2, 1, false, area, free, 20, 5, 1, 10),
                quote("world", 2, 2, 1, false, area, free, 20, 5, 1, 10),
                quote("world", 1, 3, 1, false, area, free, 20, 5, 1, 10),
                quote("world", 1, 2, 2, false, area, free, 20, 5, 1, 10),
                quote("world", 1, 2, 1, true, area, free, 20, 5, 1, 10),
                quote("world", 1, 2, 1, false, free, free, 20, 5, 1, 10),
                quote("world", 1, 2, 1, false, area, area, 20, 5, 1, 10),
                quote("world", 1, 2, 1, false, area, free, 21, 5, 1, 10),
                quote("world", 1, 2, 1, false, area, free, 20, 6, 1, 10),
                quote("world", 1, 2, 1, false, area, free, 20, 5, 2, 10),
                quote("world", 1, 2, 1, false, area, free, 20, 5, 1, 11));
        for (var changed : changes) {
            assertTrue(c.offer(owner, session, original, 0));
            assertFalse(c.consume(owner, session, changed, 1));
            assertFalse(c.consume(owner, session, original, 2));
        }
        assertTrue(c.offer(owner, session, original, 0));
        assertFalse(c.consume(owner, new Object(), original, 1));
        assertFalse(c.consume(owner, session, original, 2));
        assertTrue(c.offer(owner, session, original, 0));
        assertFalse(c.consume(owner, session, original, EconomyConfirmations.TTL_NANOS));
        assertTrue(c.offer(owner, session, original, 0));
        assertTrue(c.consume(owner, session, original, 1));
        assertFalse(c.consume(owner, session, original, 2));
    }
}
