package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimTerritoriesTest {
    @TempDir Path dir;
    private ClaimStore store() { return new ClaimStore(dir, Logger.getAnonymousLogger()); }
    private Claim claim(int x, int z, String owner) { return new Claim("world", x, z, owner, 1000); }

    @Test void sidesMergeTransitivelyButNotCornersWorldsOrOwners() {
        var a = claim(0, 0, "Steve"); var b = claim(1, 0, "steve"); var c = claim(1, 1, "STEVE");
        var corner = claim(2, 2, "Steve"); var other = claim(-1, 0, "Alex");
        var nether = new Claim("nether", 0, 0, "Steve", 1000);
        var groups = ClaimTerritories.groups(List.of(a, b, c, corner, other, nether));
        assertEquals(4, groups.size());
        assertEquals(3, groups.stream().filter(g -> g.contains(a)).findFirst().orElseThrow().size());
    }
    @Test void gapRequiresOneEmptyChunkIncludingDiagonalsButIgnoresOwnAndOtherWorlds() {
        var s = store(); s.add(claim(0, 0, "Alex"));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            assertTrue(s.foreignNearby("world", x, z, "Steve", List.of()));
            assertFalse(s.foreignNearby("world", x, z, "alex", List.of()));
        }
        assertFalse(s.foreignNearby("world", 2, 0, "Steve", List.of()));
        assertFalse(s.foreignNearby("world", 2, 2, "Steve", List.of()));
        assertFalse(s.foreignNearby("nether", 0, 0, "Steve", List.of()));
    }
    @Test void negativeAndExtremeCoordinatesDoNotWrapAcrossWorld() {
        var s = store(); s.add(claim(Integer.MIN_VALUE, 0, "Alex"));
        assertTrue(s.foreignNearby("world", Integer.MIN_VALUE + 1, 0, "Steve", List.of()));
        assertFalse(s.foreignNearby("world", Integer.MAX_VALUE, 0, "Steve", List.of()));
        s.add(claim(Integer.MAX_VALUE, 0, "Alex"));
        assertEquals(2, ClaimTerritories.groups(s.all()).size());
    }
    @Test void newChunkInheritsExistingRegionWithoutResettingMembersOrRentDates() {
        var s = store(); var a = claim(0, 0, "Steve");
        a.members.add("Friend"); a.setRole("Friend", Claim.Role.CONTAINER); a.greeting = "Дом"; a.salePrice = 50;
        s.add(a); var b = claim(1, 0, "Steve"); b.paidUntil = 7777; s.add(b);
        assertEquals(2, s.territory(a).size());
        assertEquals(Claim.Role.CONTAINER, b.memberRole("friend"));
        assertEquals("Дом", b.greeting); assertEquals(7777, b.paidUntil);
        assertEquals(0, a.salePrice); assertEquals(0, b.salePrice);
        assertEquals(2, s.count("STEVE"));
    }
    @Test void bridgeKeepsOnlyCommonMembersWithMinimumRoleAndCancelsStaleSales() {
        var s = store(); var a = claim(0, 0, "Steve"); var b = claim(2, 0, "Steve");
        a.members.addAll(List.of("Shared", "OnlyA")); b.members.addAll(List.of("shared", "OnlyB"));
        a.setRole("Shared", Claim.Role.CONTAINER); b.setRole("Shared", Claim.Role.ACCESS);
        a.salePrice = 30; b.salePrice = 70; s.add(a); s.add(b); s.add(claim(1, 0, "Steve"));
        for (Claim c : s.territory(a)) {
            assertEquals(1, c.members.size()); assertEquals(Claim.Role.ACCESS, c.memberRole("SHARED"));
            assertNull(c.memberRole("OnlyA")); assertNull(c.memberRole("OnlyB")); assertEquals(0, c.salePrice);
        }
    }
    @Test void deletingBridgeSplitsTerritoryAndCancelsAllListings() {
        var s = store(); var a = claim(0, 0, "Steve"); var b = claim(1, 0, "Steve"); var c = claim(2, 0, "Steve");
        s.add(a); s.add(b); s.add(c); s.all().forEach(t -> t.salePrice = 100);
        s.remove(b); assertEquals(1, s.territory(a).size()); assertEquals(1, s.territory(c).size());
        assertEquals(0, a.salePrice); assertEquals(0, c.salePrice);
    }
    @Test void transferChecksAllMovingChunksAndIgnoresInternalNeighbours() {
        var s = store(); var a = claim(0, 0, "Old"); var b = claim(1, 0, "Old");
        s.add(a); s.add(b); var moving = s.territory(a);
        assertFalse(s.foreignNearby("world", 0, 0, "New", moving));
        s.add(claim(2, 1, "Old")); // Corner does not join, but would become a foreign neighbour.
        assertTrue(s.foreignNearby("world", 1, 0, "New", moving));
    }
    @Test void migrationPreservesLandMoneyDebtDatesAndAvoidsCheapExpandedOldListings() throws Exception {
        Files.writeString(dir.resolve("claims-data.json"), """
            {"version":2,"claims":[
              {"w":"world","x":0,"z":0,"o":"Steve","p":1000,"d":2,"s":10,"m":["Shared","OnlyA"],"mr":{"Shared":"CONTAINER"}},
              {"w":"world","x":1,"z":0,"o":"Steve","p":2000,"d":1,"s":10,"m":["Shared"]},
              {"w":"world","x":2,"z":0,"o":"Other","p":3000}],"treasury":{"Steve":70}}
            """);
        var s = store(); s.load(); assertEquals(3, s.all().size()); assertEquals(70, s.treasuryOf("Steve"));
        assertEquals(1000, s.claimAt("world", 0, 0).paidUntil); assertEquals(2, s.claimAt("world", 0, 0).debtDays);
        assertEquals(0, s.claimAt("world", 0, 0).salePrice);
        assertEquals(Claim.Role.CONTAINER, s.claimAt("world", 1, 0).memberRole("Shared"));
        assertNull(s.claimAt("world", 1, 0).memberRole("OnlyA"));
        assertEquals("Other", s.claimAt("world", 2, 0).owner); // No destructive foreign eviction.
        s.all().stream().filter(c -> c.isOwner("Steve")).forEach(c -> c.salePrice = 50);
        s.save(); var loaded = store(); loaded.load(); assertEquals(50, loaded.claimAt("world", 1, 0).salePrice);
    }
    @Test void bordersOmitInternalSeamsAndHandleNegativeCoordinates() {
        var s = store(); s.add(claim(-1, 0, "Steve")); s.add(claim(0, 0, "Steve")); s.add(claim(1, 0, "Steve"));
        var edges = ClaimBorders.visible(0, 0, (x, z) -> s.claimAt("world", x, z));
        assertEquals(8, edges.size());
        assertFalse(edges.stream().anyMatch(e -> e.x1() == 0 && e.x2() == 0));
        assertEquals(4, ClaimBorders.visible(10, 10, (x, z) -> null).size());
    }
    @Test void menuShowsOneCardPerConnectedTerritory() {
        assertEquals(1, ClaimMenuModel.view(List.of(claim(0, 0, "Steve"), claim(1, 0, "Steve")), "Steve", ClaimMenuModel.Tab.MINE).size());
    }
}
