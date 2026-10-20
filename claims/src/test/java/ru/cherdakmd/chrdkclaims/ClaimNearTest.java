package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Поиск приватов вокруг игрока (/claim near) — чистая геометрия. */
class ClaimNearTest {

    private static Claim claim(String world, int x, int z, String owner) {
        return new Claim(world, x, z, owner, 1_000L);
    }

    @Test
    void findsOnlySameWorldInsideRadius() {
        List<Claim> claims = List.of(
                claim("world", 1, 0, "Steve"),
                claim("world", 9, 0, "Alex"),
                claim("world_nether", 0, 0, "Steve"));
        ClaimNear.Result result = ClaimNear.search(claims, "world", 0, 0, 5, null);
        assertEquals(1, result.total());
        assertEquals(1, result.entries().get(0).distance());
        assertEquals("Steve", result.entries().get(0).claim().owner);
    }

    @Test
    void radiusUsesChebyshevDistance() {
        List<Claim> claims = List.of(
                claim("world", 5, 5, "Diagonal"),
                claim("world", 5, 0, "Edge"),
                claim("world", 6, 0, "Far"));
        ClaimNear.Result result = ClaimNear.search(claims, "world", 0, 0, 5, null);
        assertEquals(2, result.total());
        assertEquals(List.of("Diagonal", "Edge"),
                result.entries().stream().map(entry -> entry.claim().owner).toList());
    }

    @Test
    void excludesOwnClaimsCaseInsensitively() {
        List<Claim> claims = List.of(
                claim("world", 1, 0, "Steve"),
                claim("world", 1, 1, "Alex"));
        ClaimNear.Result result = ClaimNear.search(claims, "world", 0, 0, 3, "steve");
        assertEquals(1, result.total());
        assertEquals("Alex", result.entries().get(0).claim().owner);
    }

    @Test
    void sortsNearestFirstThenByOwner() {
        List<Claim> claims = List.of(
                claim("world", 3, 0, "Zed"),
                claim("world", 1, 0, "Bob"),
                claim("world", 0, 1, "Alex"));
        ClaimNear.Result result = ClaimNear.search(claims, "world", 0, 0, 5, null);
        assertEquals(List.of("Alex", "Bob", "Zed"),
                result.entries().stream().map(entry -> entry.claim().owner).toList());
        assertEquals(List.of(1, 1, 3),
                result.entries().stream().map(ClaimNear.Entry::distance).toList());
    }

    @Test
    void capsRowsButKeepsTotal() {
        List<Claim> claims = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            claims.add(claim("world", index + 1, 0, "Owner" + index));
        }
        ClaimNear.Result result = ClaimNear.search(claims, "world", 0, 0, 16, null);
        assertEquals(14, result.total());
        assertEquals(ClaimNear.MAX_RESULTS, result.entries().size());
    }

    @Test
    void clampsRadiusAndIgnoresBrokenInput() {
        assertEquals(ClaimNear.MIN_RADIUS, ClaimNear.clampRadius(0));
        assertEquals(ClaimNear.MAX_RADIUS, ClaimNear.clampRadius(999));
        assertEquals(4, ClaimNear.clampRadius(4));

        assertTrue(ClaimNear.search(null, "world", 0, 0, 5, null).entries().isEmpty());
        assertTrue(ClaimNear.search(List.of(claim("world", 0, 0, "Steve")), null, 0, 0, 5, null)
                .entries().isEmpty());
        ClaimNear.Result withNulls = ClaimNear.search(
                Arrays.asList(null, claim("world", 0, 0, "Steve")), "world", 0, 0, 1, null);
        assertEquals(1, withNulls.total(), "битые записи не должны ронять поиск");
    }
}
