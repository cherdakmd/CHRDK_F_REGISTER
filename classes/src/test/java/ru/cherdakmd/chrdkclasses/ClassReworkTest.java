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
        SkillSource skills=new SkillSource(){ public int totalLevel(UUID id){return 1000;} public int level(UUID id,String key){return 100;} };
        TalentService service=new TalentService(ClassConfig.defaults(),store,skills);
        service.enableDevelopment(LegacyClassRules.capture(ClassConfig.defaults()));
        assertTrue(service.development().prepare(ID,NOW));
        assertTrue(service.choose(ID,"warrior",NOW).ok());
        return service;
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
    @Test void rulesAreFrozenAndCorruptionFailsClosed(@TempDir Path dir) throws Exception {
        var rules=LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null);
        assertEquals(rules,LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null));
        Files.writeString(dir.resolve("classes-legacy-rules.json"),"{}");
        assertThrows(IllegalStateException.class,()->LegacyRulesFile.loadOrCreate(dir,ClassConfig.defaults(),null));
        assertEquals("{}",Files.readString(dir.resolve("classes-legacy-rules.json")));
    }
}
