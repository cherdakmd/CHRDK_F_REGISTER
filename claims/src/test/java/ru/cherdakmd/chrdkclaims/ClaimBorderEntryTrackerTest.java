package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimBorderEntryTrackerTest {
    private final UUID id=UUID.randomUUID();
    private Claim claim(String world,int x,int z,String owner){return new Claim(world,x,z,owner,1000);}
    @Test void ownerEntryOnlyOnceWhileWalkingAcrossMergedChunks() {
        var tracker=new ClaimBorderEntryTracker();var a=claim("world",0,0,"Steve");var b=claim("world",1,0,"steve");
        ClaimBorders.Lookup lookup=(x,z)->z==0?(x==0?a:x==1?b:null):null;
        assertTrue(tracker.entered(id,"STEVE",a,lookup));assertFalse(tracker.entered(id,"Steve",a,lookup));
        assertFalse(tracker.entered(id,"Steve",b,lookup));assertFalse(tracker.entered(id,"Steve",a,lookup));
    }
    @Test void freeOrForeignLandResetsEntryAndNeverTriggersIt() {
        var tracker=new ClaimBorderEntryTracker();var own=claim("world",0,0,"Steve");var foreign=claim("world",2,0,"Alex");
        ClaimBorders.Lookup lookup=(x,z)->x==0?own:foreign;
        assertFalse(tracker.entered(id,"Friend",own,lookup));
        assertTrue(tracker.entered(id,"Steve",own,lookup));assertFalse(tracker.entered(id,"Steve",null,lookup));
        assertTrue(tracker.entered(id,"Steve",own,lookup));assertFalse(tracker.entered(id,"Steve",foreign,lookup));
        assertTrue(tracker.entered(id,"Steve",own,lookup));
    }
    @Test void teleportInsideOneLargeTerritoryDoesNotRestartButDisconnectedOneDoes() {
        Map<Long,Claim> map=new HashMap<>();for(int x=0;x<10;x++){var c=claim("world",x,0,"Steve");map.put(c.key(),c);}
        var isolated=claim("world",12,0,"Steve");map.put(isolated.key(),isolated);
        ClaimBorders.Lookup lookup=(x,z)->map.get(ClaimMath.chunkKey(x,z));var tracker=new ClaimBorderEntryTracker();
        assertTrue(tracker.entered(id,"Steve",map.get(0L),lookup));
        assertFalse(tracker.entered(id,"Steve",map.get(ClaimMath.chunkKey(9,0)),lookup));
        assertTrue(tracker.entered(id,"Steve",isolated,lookup));
    }
    @Test void worldChangeOrDeathOrLogoutAllowsFreshOwnEntry() {
        var tracker=new ClaimBorderEntryTracker();var a=claim("world",0,0,"Steve");var b=claim("nether",0,0,"Steve");
        assertTrue(tracker.entered(id,"Steve",a,(x,z)->a));assertTrue(tracker.entered(id,"Steve",b,(x,z)->b));
        tracker.clear(id);assertEquals(0,tracker.tracked());assertTrue(tracker.entered(id,"Steve",b,(x,z)->b));
        tracker.clearAll();assertEquals(0,tracker.tracked());
    }
    @Test void releasedPreviousChunkDoesNotSuppressNextEntry() {
        var tracker=new ClaimBorderEntryTracker();var a=claim("world",0,0,"Steve");var b=claim("world",1,0,"Steve");
        assertTrue(tracker.entered(id,"Steve",a,(x,z)->a));
        assertTrue(tracker.entered(id,"Steve",b,(x,z)->x==1?b:null));
    }
    @Test void ownershipTransferStartsANewPersonalVisit() {
        var tracker=new ClaimBorderEntryTracker();var a=claim("world",0,0,"Steve");
        assertTrue(tracker.entered(id,"Steve",a,(x,z)->a));
        a.owner="Alex";assertFalse(tracker.entered(id,"Steve",a,(x,z)->a));assertEquals(0,tracker.tracked());
        a.owner="Steve";assertTrue(tracker.entered(id,"Steve",a,(x,z)->a));
    }
}
