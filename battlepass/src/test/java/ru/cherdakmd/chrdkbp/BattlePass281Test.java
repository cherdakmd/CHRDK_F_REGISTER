package ru.cherdakmd.chrdkbp;

import java.util.*;
import java.nio.file.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BattlePass281Test {
    @TempDir Path dir;
    List<Map<?,?>> weeks(String resource) throws Exception {
        try(var stream=getClass().getResourceAsStream("/"+resource)) {
            assertNotNull(stream);var cfg=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
            var result=new ArrayList<Map<?,?>>();for(Object row:cfg.getList("battlepass.weeks"))result.add((Map<?,?>)row);return result;
        }
    }
    @Test void presetsAreSubstantiallyStrongerWithoutChangingExistingGoalsOrIds() throws Exception {
        var old=BattlePassConfig.parseWeeks(weeks("battlepass-default-legacy-1.4.280.yml"));var next=BattlePassConfig.parseWeeksDetailed(weeks("config.yml"));
        assertTrue(next.problems().isEmpty(),next.problems().toString());assertEquals(5,next.weeks().size());
        var all=next.weeks().stream().flatMap(w->w.missions().stream()).toList();assertEquals(23,all.size());
        assertEquals(222,all.stream().mapToInt(BattlePassConfig.MissionDef::freeHours).sum());
        assertEquals(216,all.stream().mapToInt(BattlePassConfig.MissionDef::vipHours).sum());
        assertEquals(366,all.stream().mapToInt(BattlePassConfig.MissionDef::premiumHours).sum());
        for(var w:old)for(var m:w.missions()) {
            var n=all.stream().filter(v->v.id().equals(m.id())).findFirst().orElseThrow();
            assertEquals(m.target(),n.target());assertEquals(m.type(),n.type());assertEquals(m.matchKey(),n.matchKey());
            assertEquals(Math.min(24,m.freeHours()*6),n.freeHours());
        }
        assertTrue(all.stream().allMatch(m->m.premiumProducts().isEmpty()&&m.freeHours()<=24&&m.premiumHours()<=24));
    }
    @Test void stockMigrationNeverOverwritesCustomListsOrEmptyLegacy() {
        var old=List.of(Map.of("id","one","target",16));
        assertTrue(BattlePassConfig.stockUpgrade(List.of(Map.of("id","one","target",16)),old));
        assertFalse(BattlePassConfig.stockUpgrade(List.of(Map.of("id","one","target",17)),old));
        assertFalse(BattlePassConfig.stockUpgrade(List.of(),List.of()));assertFalse(BattlePassConfig.stockUpgrade(null,old));
    }
    @Test void receiptsAreStableButScopedByUuidSeasonMissionAndTrack() {
        UUID id=UUID.randomUUID();String first=BattlePassClaims.receipt(100,id,"one","FREE");
        assertTrue(first.matches("bp:[a-f0-9]{64}"));assertEquals(first,BattlePassClaims.receipt(100,id,"one","FREE"));
        assertNotEquals(first,BattlePassClaims.receipt(101,id,"one","FREE"));assertNotEquals(first,BattlePassClaims.receipt(100,UUID.randomUUID(),"one","FREE"));
        assertNotEquals(first,BattlePassClaims.receipt(100,id,"two","FREE"));assertNotEquals(first,BattlePassClaims.receipt(100,id,"one","VIP"));
    }
    @Test void lockedTracksAreNotCountedAsRequiredForFreePlayers() {
        var d=new BattlePassConfig.MissionDef("one","One",BattlePassConfig.MissionType.MINE,"stone",10,6,8);
        var v=new BattlePassView.Mission(d,10,true,false,false);assertEquals(1,v.availableTracks());assertEquals(2,v.totalTracks());
        assertTrue(BattlePassFilter.DONE.includes(true,v));assertFalse(BattlePassFilter.ACTIVE.includes(true,v));
        assertTrue(BattlePassFilter.FUTURE.includes(false,v));assertFalse(BattlePassFilter.READY.includes(false,v));
        BattlePassFilter filter=BattlePassFilter.ALL;for(int i=0;i<5;i++)filter=filter.next();assertEquals(BattlePassFilter.ALL,filter);
    }
    @Test void oldProgressAndClaimsRemainAndOverflowCannotReduceProgress() {
        UUID id=UUID.randomUUID();var store=new BattlePassStore(dir,Logger.getAnonymousLogger());store.setProgress(id,"one",10,10);store.markClaimed(id,"one","FREE");store.save();
        var restored=new BattlePassStore(dir,Logger.getAnonymousLogger());restored.load();assertEquals(10,restored.progressOf(id,"one"));assertTrue(restored.isClaimed(id,"one","FREE"));
        restored.addProgress(id,"big",Integer.MAX_VALUE-1,Integer.MAX_VALUE);assertEquals(Integer.MAX_VALUE,restored.addProgress(id,"big",100,Integer.MAX_VALUE));
    }
    @Test void malformedRowsFailClosedWithoutDiscardingOriginalFile() throws Exception {
        for(String json:List.of("null","{\"not-a-uuid\":{}}","{\""+UUID.randomUUID()+"\":{\"progress\":{\"one\":-1}}}")) {
            Path file=dir.resolve("battlepass-data.json");Files.writeString(file,json);var store=new BattlePassStore(dir,Logger.getAnonymousLogger());store.load();
            assertFalse(store.healthy());store.save();assertEquals(json,Files.readString(file));
        }
    }
    @Test void corruptedFileIsNotReplacedWithEmptyProgress() throws Exception {
        Path file=dir.resolve("battlepass-data.json");Files.writeString(file,"{broken");var store=new BattlePassStore(dir,Logger.getAnonymousLogger());store.load();
        assertFalse(store.healthy());store.save();assertEquals("{broken",Files.readString(file));
    }
}
