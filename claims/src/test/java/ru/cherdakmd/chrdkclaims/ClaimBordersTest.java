package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimBordersTest {
    private Claim claim(int x, int z, String owner) { return new Claim("world", x, z, owner, 1000); }
    private Map<Long, Claim> claims(Claim... entries) {
        Map<Long, Claim> result = new HashMap<>();
        for (Claim c : entries) result.put(c.key(), c);
        return result;
    }
    private ClaimBorders.Lookup lookup(Map<Long, Claim> map) { return (x, z) -> map.get(ClaimMath.chunkKey(x, z)); }

    @Test void fullLargeTerritoryIsVisibleEvenFromTheMiddle() {
        Map<Long, Claim> map = new HashMap<>();
        for (int x = -5; x < 5; x++) for (int z = -5; z < 5; z++) {
            Claim c = claim(x, z, "Steve"); map.put(c.key(), c);
        }
        var outline = ClaimBorders.outline(0, 0, lookup(map));
        assertEquals(100, outline.chunks().size());
        assertEquals(40, outline.edges().size());
        assertTrue(outline.edges().stream().anyMatch(e -> e.x1() == -80 && e.x2() == -80));
        assertTrue(outline.edges().stream().anyMatch(e -> e.x1() == 80 && e.x2() == 80));
    }
    @Test void longMergedStripHasBothEndsWithoutFiveByFiveCutoff() {
        Map<Long, Claim> map = new HashMap<>();
        for (int x = 0; x < 48; x++) { Claim c = claim(x, 0, "Steve"); map.put(c.key(), c); }
        var outline = ClaimBorders.outline(24, 0, lookup(map));
        assertEquals(48, outline.chunks().size());assertEquals(98, outline.edges().size());
        assertTrue(outline.edges().stream().anyMatch(e -> e.x1() == 0 && e.x2() == 0));
        assertTrue(outline.edges().stream().anyMatch(e -> e.x1() == 768 && e.x2() == 768));
    }
    @Test void disconnectedCornersAndOtherOwnersAreNotHighlighted() {
        var map = claims(claim(0, 0, "Steve"), claim(1, 0, "steve"), claim(2, 1, "Steve"),
                claim(0, 2, "Steve"), claim(0, 1, "Alex"));
        var outline = ClaimBorders.outline(0, 0, lookup(map));
        assertEquals(2, outline.chunks().size());assertEquals(6, outline.edges().size());
        assertFalse(outline.contains(2, 1));assertFalse(outline.contains(0, 2));
        assertFalse(outline.edges().stream().anyMatch(e -> e.x1() == 16 && e.x2() == 16));
    }
    @Test void outlineOrderDoesNotDependOnEntryChunk() {
        var map=claims(claim(0,0,"Steve"),claim(1,0,"Steve"),claim(1,1,"Steve"));
        assertEquals(ClaimBorders.visible(0,0,lookup(map)),ClaimBorders.visible(1,1,lookup(map)));
    }
    @Test void otherWorldCannotBecomePartOfTheOutline() {
        var map = claims(claim(0, 0, "Steve"), new Claim("nether", 1, 0, "Steve", 1000));
        assertEquals(1, ClaimBorders.outline(0, 0, lookup(map)).chunks().size());
    }
    @Test void courtyardHasRealBordersButMergedChunksHaveNoSeams() {
        Map<Long, Claim> map = new HashMap<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) if (x != 1 || z != 1) {
            Claim c = claim(x, z, "Steve"); map.put(c.key(), c);
        }
        var outline = ClaimBorders.outline(0, 0, lookup(map));
        assertEquals(8, outline.chunks().size());assertEquals(16, outline.edges().size());
        assertFalse(outline.contains(1, 1));
    }
    @Test void freeChunkKeepsItsOwnPreview() {
        var outline = ClaimBorders.outline(-2, -3, (x,z) -> null);
        assertEquals(4, outline.edges().size());assertTrue(outline.contains(-2,-3));assertEquals("", outline.owner());
        assertTrue(outline.edges().stream().allMatch(e -> e.x1() >= -32 && e.x2() <= -16 && e.z1() >= -48 && e.z2() <= -32));
    }
    @Test void extremeCoordinatesCannotWrapOrOverflow() {
        var map = claims(claim(Integer.MIN_VALUE, 0, "Steve"), claim(Integer.MAX_VALUE, 0, "Steve"));
        var outline = ClaimBorders.outline(Integer.MAX_VALUE,0,lookup(map));
        assertEquals(1, outline.chunks().size());
        assertTrue(outline.edges().stream().anyMatch(e -> e.x1() == ((long)Integer.MAX_VALUE+1)*16));
        assertFalse(outline.contains(Integer.MIN_VALUE,0));
    }
    @Test void smallContourUsesThreeLayersAndCornerSparks() {
        var frame = ClaimBorderParticles.frame(ClaimBorders.visible(0,0,(x,z)->null),0);
        assertEquals(116,frame.points().size());assertEquals(0,frame.nextCursor());
        assertEquals(8,frame.points().stream().filter(ClaimBorderParticles.Point::spark).count());
        assertEquals(Set.of(.15,1.4,2.65),frame.points().stream().filter(p->!p.spark()).map(ClaimBorderParticles.Point::height).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void packetBudgetRotatesInsteadOfDroppingDistantEdges() {
        List<ClaimBorders.Edge> edges = new ArrayList<>();
        for(int i=0;i<1000;i++)edges.add(new ClaimBorders.Edge(i*32L,0,i*32L,16));
        Set<Double> visited = new HashSet<>();long cursor=0;
        for(int pulse=0;pulse<57;pulse++) {
            var frame=ClaimBorderParticles.frame(edges,cursor);cursor=frame.nextCursor();
            assertTrue(frame.points().size()<=512);
            frame.points().forEach(p->visited.add(p.x()));
        }
        assertEquals(1000,visited.size());
        assertTrue(visited.contains(999*32.0));
    }
    @Test void minimumThreeSecondsCoversAllSamplesOfStandardFortyEightChunkStrip() {
        Map<Long,Claim> map=new HashMap<>();
        for(int i=0;i<48;i++){var c=claim(i,0,"Steve");map.put(c.key(),c);}
        var edges=ClaimBorders.visible(0,0,lookup(map));
        Set<ClaimBorderParticles.Point> expected=new HashSet<>(),seen=new HashSet<>();
        long cursor=0;
        // Один полный обход включает все 98 рёбер, три ряда и оба маркера каждого ребра.
        for(int i=0;i<6;i++){var f=ClaimBorderParticles.frame(edges,cursor);seen.addAll(f.points());cursor=f.nextCursor();}
        for(var edge:edges){
            for(double height:new double[]{.15,1.4,2.65})for(int offset=0;offset<=16;offset+=2)
                expected.add(new ClaimBorderParticles.Point(edge.x1()+(edge.x2()-edge.x1())*offset/16.0,height,edge.z1()+(edge.z2()-edge.z1())*offset/16.0,false));
            expected.add(new ClaimBorderParticles.Point(edge.x1(),3.9,edge.z1(),true));
            expected.add(new ClaimBorderParticles.Point(edge.x2(),3.9,edge.z2(),true));
        }
        assertEquals(expected,seen);
    }
    @Test void emptyAndNegativeCursorFramesAreSafe() {
        assertTrue(ClaimBorderParticles.frame(List.of(),0).points().isEmpty());
        var edges=ClaimBorders.visible(0,0,(x,z)->null);
        assertEquals(ClaimBorderParticles.frame(edges,Math.floorMod(Long.MIN_VALUE,116L)),ClaimBorderParticles.frame(edges,Long.MIN_VALUE));
    }
    @Test void storeRevisionInvalidatesVisualCacheAfterLandMutation(@TempDir Path dir) {
        var store=new ClaimStore(dir,null);long before=store.borderRevision();
        var c=claim(0,0,"Steve");store.add(c);assertTrue(store.borderRevision()>before);
        before=store.borderRevision();store.remove(c);assertTrue(store.borderRevision()>before);
    }
    @Test void entrySettingIsEnabledForOldConfigsAndIndependentOfTextNotices() {
        var yaml=new YamlConfiguration();yaml.set("general.enter-notice",false);yaml.set("general.leave-notice",false);yaml.set("general.notice-own",false);
        assertTrue(ClaimsConfig.parse(yaml,null).borderOnOwnEntry);
        yaml.set("general.border-on-own-entry",false);assertFalse(ClaimsConfig.parse(yaml,null).borderOnOwnEntry);
    }
}
