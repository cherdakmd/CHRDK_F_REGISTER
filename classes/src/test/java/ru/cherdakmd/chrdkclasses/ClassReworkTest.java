package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClassReworkTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final long NOW=1_790_000_000_000L;
    private TalentService fresh(Path dir) {
        ClassStore store=new ClassStore(dir,null);
        SkillSource skills=new SkillSource(){ public boolean available(){return true;} public int totalLevel(UUID id){return 1000;} public int level(UUID id,String key){return 100;} };
        TalentService service=new TalentService(ClassConfig.defaults(),store,skills,rank(),achievements());
        service.enableDevelopment(LegacyClassRules.capture(ClassConfig.defaults()));
        assertTrue(service.development().prepare(ID,NOW));
        assertTrue(service.choose(ID,"warrior",NOW).ok());
        return service;
    }
    private RankSource rank() {return new RankSource(){public boolean available(){return true;}public int rankLevel(UUID id){return 0;}};}
    private AchievementSource achievements() {return new AchievementSource(){public boolean available(){return true;}public int unlockedCount(UUID id){return 0;}};}
    @Test void migrationWaitsForRealSourcesAndPreservesSpentFloor(@TempDir Path dir) {
        boolean[] available={false};
        SkillSource skills=new SkillSource(){public boolean available(){return available[0];}public int totalLevel(UUID id){return 0;}public int level(UUID id,String key){return 0;}};
        var store=new ClassStore(dir,null);store.setClass(ID,"warrior",NOW-1000);store.clearTalents(ID,NOW-100);
        store.learnTalent(ID,"warrior-stoyka-1");store.learnTalent(ID,"warrior-stoyka-2");
        int spent=LegacyClassTalents.spent("warrior",store.talents(ID));
        store.setAbilityAt(ID,NOW-10);
        var service=new TalentService(ClassConfig.defaults(),store,skills,rank(),achievements());
        service.enableDevelopment(LegacyClassRules.capture(ClassConfig.defaults()));
        assertFalse(service.development().prepare(ID,NOW));assertNull(store.development(ID));assertEquals(2,store.talentCount(ID));
        available[0]=true;assertTrue(service.development().prepare(ID,NOW));
        assertEquals("warrior",store.classOf(ID));assertEquals(Math.max(1,spent),service.earnedPoints(ID));assertEquals(0,service.spentPoints(ID));
        assertEquals(NOW-100,store.respecAt(ID));assertEquals(NOW-10,store.lastAbility(ID,"warrior"));
        assertTrue(service.learn(ID,"warrior-path-0-1").ok());
        var q=service.development().quote(ID,ClassBuild.empty("warrior"),NOW);
        assertTrue(service.development().apply(ID,q,NOW+1).ok());assertFalse(store.development(ID).firstBuildAvailable());
        assertEquals(NOW-10,store.lastAbility(ID,"warrior"));
        assertTrue(service.learn(ID,"warrior-path-0-1").ok());
        assertEquals("respec-cooldown",service.development().apply(ID,service.development().quote(ID,ClassBuild.empty("warrior"),NOW+2),NOW+3).key());
        int earned=service.earnedPoints(ID);assertTrue(service.development().prepare(ID,NOW+4));assertEquals(earned,service.earnedPoints(ID));
    }
    @Test void rankBoundariesAreStrict() {
        var node=ClassTrees.talent("warrior-path-0-1");
        assertThrows(IllegalArgumentException.class,()->node.rankCost(0));assertThrows(IllegalArgumentException.class,()->node.rankCost(4));
        assertEquals(6,node.totalCost(3));
    }
    @Test void abilityLevelAndMasteryExclusivityAreEnforced(@TempDir Path dir) {
        var s=fresh(dir);
        assertFalse(s.development().problems(ID,ClassBuild.empty("warrior").equip(1,"warrior-3")).isEmpty());
        s.store().awardClassXp(ID,ClassDevelopment.Source.COMBAT,ClassDevelopmentRules.MAX_XP);
        Map<String,Integer> ranks=new HashMap<>();
        for(int path=0;path<2;path++)for(int tier=1;tier<=5;tier++)ranks.put("warrior-path-"+path+"-"+tier,1);
        assertTrue(s.development().problems(ID,new ClassBuild("warrior",ranks,List.of("warrior-3","warrior-4"))).stream().anyMatch(r->r.key().equals("development-exclusive")));
    }
    @Test void xpBarUsesCurrentLevelAndStopsAtCap() {
        assertEquals(0,ClassDevelopmentRules.progressSegments(0,10));assertEquals(5,ClassDevelopmentRules.progressSegments(50,10));
        assertEquals(0,ClassDevelopmentRules.progressSegments(100,10));assertEquals(10,ClassDevelopmentRules.progressSegments(ClassDevelopmentRules.MAX_XP,10));
        assertThrows(IllegalArgumentException.class,()->ClassDevelopmentRules.progressSegments(0,0));
    }
    @Test void catalogHas180MeaningfulRankedNodes() {
        Set<String> ids=new HashSet<>();
        for(PlayerClass role:PlayerClass.values()) {
            assertEquals(45,ClassTrees.talents(role.id()).size());
            assertEquals(9,ClassTrees.branches(role.id()).size());
            int cost=0;
            for(Talent node:ClassTrees.talents(role.id())) {
                assertTrue(ids.add(node.id()));assertTrue(ClassTrees.ceiling(role.id(),node.kind())>0);
                assertEquals(node.tier()==5?1:3,node.maxRank());
                if(node.hasParent())assertNotNull(ClassTrees.talent(node.requires()));
                cost+=node.totalCost(node.maxRank());
            }
            assertEquals(261,cost);assertTrue(cost>LegacyClassMigration.MAX_OLD_POINTS);
            for(int page=0;page<3;page++)assertEquals(15,ClassTrees.page(role.id(),page).size());
        }
        assertEquals(180,ids.size());
    }
    @Test void rankPricesCannotCopyOrRefundPoints(@TempDir Path dir) {
        var service=fresh(dir);String key="warrior-path-0-1";
        assertTrue(service.learn(ID,key).ok());assertEquals(1,service.spentPoints(ID));
        assertFalse(service.learn(ID,key).ok());
        service.store().awardClassXp(ID,ClassDevelopment.Source.COMBAT,ClassDevelopmentRules.xpForLevel(10));
        assertTrue(service.learn(ID,key).ok());assertEquals(3,service.spentPoints(ID));
        assertTrue(service.learn(ID,key).ok());assertEquals(6,service.spentPoints(ID));
        assertFalse(service.learn(ID,key).ok());assertEquals(6,service.spentPoints(ID));
    }
    @Test void skillsDoNotMintClassXp(@TempDir Path dir) {
        var s=fresh(dir);assertEquals(1,s.level(ID));assertEquals(1,s.earnedPoints(ID));
        assertEquals(0,s.store().development(ID).xp());
    }
    @Test void structuralBuildRejectsForeignDuplicateAndBadRanks() {
        assertThrows(IllegalArgumentException.class,()->new ClassBuild("warrior",Map.of("mage-path-0-1",1),List.of("warrior","warrior-2")));
        assertThrows(IllegalArgumentException.class,()->new ClassBuild("warrior",Map.of(),List.of("warrior","warrior")));
        assertThrows(IllegalArgumentException.class,()->ClassBuild.empty("warrior").rank("warrior-path-0-1",4));
        assertThrows(IllegalArgumentException.class,()->ClassBuild.empty("warrior").equip(1,"mage"));
    }
    @Test void lockedParentLevelAndBudgetAreRevalidated(@TempDir Path dir) {
        var s=fresh(dir);
        var errors=s.development().problems(ID,ClassBuild.empty("warrior").rank("warrior-path-0-5",1));
        assertTrue(errors.stream().anyMatch(e->e.key().equals("development-level")));
        assertTrue(errors.stream().anyMatch(e->e.key().equals("talent-locked")));
        assertTrue(errors.stream().anyMatch(e->e.key().equals("talent-points")));
    }
    @Test void confirmationIsBoundToPlayerTimeAndRevision(@TempDir Path dir) {
        var s=fresh(dir);assertTrue(s.learn(ID,"warrior-path-0-1").ok());
        var target=ClassBuild.empty("warrior");var q=s.development().quote(ID,target,NOW);
        assertFalse(s.development().apply(UUID.randomUUID(),q,NOW+1).ok());
        assertFalse(s.development().apply(ID,q,NOW+30_000).ok());
        s.store().awardClassXp(ID,ClassDevelopment.Source.COMBAT,1);
        assertFalse(s.development().apply(ID,q,NOW+1).ok());
        var fresh=s.development().quote(ID,target,NOW+2);
        assertTrue(s.development().apply(ID,fresh,NOW+3).ok());
        assertFalse(s.development().apply(ID,fresh,NOW+4).ok());
        assertEquals(0,s.spentPoints(ID));
    }
    @Test void buildsCannotBypassCooldownOrResetAbility(@TempDir Path dir) {
        var s=fresh(dir);s.learn(ID,"warrior-path-0-1");s.store().saveBuild(ID,1);
        s.store().useAbility(ID,"warrior",NOW);
        assertTrue(s.development().apply(ID,s.development().quote(ID,ClassBuild.empty("warrior"),NOW),NOW+1).ok());
        var target=s.store().savedBuilds(ID).get(1);
        assertEquals("respec-cooldown",s.development().apply(ID,s.development().quote(ID,target,NOW+2),NOW+3).key());
        assertEquals(NOW,s.store().lastAbility(ID,"warrior"));
        assertEquals(1,s.store().savedBuilds(ID).get(1).spent());
    }
    @Test void savedBuildsAndCooldownsRoundTrip(@TempDir Path dir) {
        var s=fresh(dir);s.learn(ID,"warrior-path-0-1");
        s.store().saveBuild(ID,3);s.store().useAbility(ID,"warrior-4",NOW);s.store().advanceMilestone(ID,"warrior",10);
        s.store().flush();var again=new ClassStore(dir,null);again.load();
        assertEquals(s.store().build(ID),again.build(ID));assertEquals(s.store().savedBuilds(ID),again.savedBuilds(ID));
        assertEquals(NOW,again.lastAbility(ID,"warrior-4"));assertEquals(10,again.milestone(ID,"warrior"));
    }
    @Test void sixReceiptsCannotBeRepeatedOnOtherClass(@TempDir Path dir) {
        var s=fresh(dir);
        for(int i=0;i<200;i++)s.store().advanceMilestone(ID,"warrior",10);
        assertEquals(6,s.store().development(ID).trialPoints());
        long xp=s.store().development(ID).xp();
        for(int i=0;i<200;i++)s.store().advanceMilestone(ID,"mage",10);
        assertEquals(6,s.store().development(ID).trialPoints());assertEquals(xp,s.store().development(ID).xp());
    }
    @Test void newPassivesNeverExceedFormerRoleCeilings(@TempDir Path dir) {
        var s=fresh(dir);s.store().awardClassXp(ID,ClassDevelopment.Source.COMBAT,ClassDevelopmentRules.MAX_XP);
        for(Talent node:ClassTrees.talents("warrior"))if(node.tier()==1)for(int i=0;i<3;i++)s.learn(ID,node.id());
        for(var e:s.effects(ID).entrySet())assertTrue(e.getValue()<=ClassTrees.ceiling("warrior",e.getKey())+1e-9);
    }
    @Test void corruptedModernBuildsCannotBeOverwritten(@TempDir Path dir) throws Exception {
        var service=fresh(dir.resolve("source"));service.learn(ID,"warrior-path-0-1");
        String valid=service.store().snapshotJson();
        java.util.List<java.util.function.Consumer<com.google.gson.JsonObject>> edits=List.of(
            row->row.getAsJsonObject("build").getAsJsonObject("ranks").addProperty("warrior-path-0-1",3),
            row->row.getAsJsonObject("build").getAsJsonObject("ranks").addProperty("warrior-path-0-1",1.5),
            row->row.getAsJsonObject("build").getAsJsonArray("abilities").set(1,new com.google.gson.JsonPrimitive("warrior")),
            row->row.getAsJsonObject("build").addProperty("classId","mage"),
            row->row.addProperty("build-revision",-1),
            row->{var times=new com.google.gson.JsonObject();times.addProperty("unknown",NOW);row.add("ability-times",times);},
            row->{var counters=new com.google.gson.JsonObject();counters.addProperty("warrior",2001);row.add("milestones",counters);}
        );
        int index=0;
        for(var edit:edits){
            var json=com.google.gson.JsonParser.parseString(valid).getAsJsonObject();
            edit.accept(json.getAsJsonObject("players").getAsJsonObject(ID.toString()));
            Path folder=Files.createDirectories(dir.resolve("bad-"+index++));
            Path file=folder.resolve("classes.json");Files.writeString(file,json.toString());byte[] before=Files.readAllBytes(file);
            var store=new ClassStore(folder,null);assertThrows(IllegalStateException.class,store::load);store.flush();assertArrayEquals(before,Files.readAllBytes(file));
        }
    }
    @Test void rulesAreFrozenAndCorruptionFailsClosed(@TempDir Path dir) throws Exception {
        var rules=LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null);
        assertEquals(rules,LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null));
        Files.writeString(dir.resolve("classes-legacy-rules.json"),"{}");
        assertThrows(IllegalStateException.class,()->LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null));
        assertEquals("{}",Files.readString(dir.resolve("classes-legacy-rules.json")));
    }
}
