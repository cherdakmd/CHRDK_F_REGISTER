package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Контракт 262: независимые XP/сила, необратимая практика и идемпотентные разряды. */
class ProfessionDevelopmentTest {
    @TempDir Path folder;
    final UUID id=UUID.randomUUID();final long now=1_800_000_000_000L;
    ProfessionStore store(){return new ProfessionStore(folder,Logger.getLogger("Profession262"));}
    @Test void hundredLevelsHaveStrictlyIncreasingCostsAndConsistentBoundaries(){
        int previous=0;for(int level=1;level<100;level++){
            int need=ProfessionMath.needed(level,100);assertTrue(need>previous);previous=need;
            int total=ProfessionMath.totalTo(level,100);
            assertEquals(level,ProfessionMath.levelFor(total,100));
            assertEquals(level,ProfessionMath.levelFor(total+need-1,100));
            assertEquals(0,ProfessionMath.progress(total,100));
        }
        assertEquals(100,ProfessionMath.levelFor(1_000_000,100));
        assertEquals(0,ProfessionMath.needed(100,100));
        assertEquals(19_440,ProfessionMath.totalTo(25,100));
        assertEquals(191_940,ProfessionMath.totalTo(100,100));
    }
    @Test void xpNeverGrantsMoreLegacyPower(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);
        assertEquals(100,service.maxLevel(id));assertEquals(25,service.powerCap(id));
        store.setActive(id,"smith",now);store.addXp(id,Profession.SMITH,1_000_000);
        assertEquals(100,service.level(id));assertEquals(40,service.smithDiscount(id));
        store.setActive(id,"alchemist",now);store.addXp(id,Profession.ALCHEMIST,1_000_000);
        assertEquals(20,service.alchemistChance(id));
        store.setActive(id,"mason",now);store.addXp(id,Profession.MASON,1_000_000);assertEquals(15,service.masonChance(id));
        store.setActive(id,"cook",now);store.addXp(id,Profession.COOK,1_000_000);assertEquals(2,service.cookSaturation(id));
    }
    @Test void onlyActiveWorkCreatesPracticeAndAdminXpDoesNot(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);store.setActive(id,"forester",now);
        assertEquals(0,service.awardSource(id,Profession.MINER,"harvest",Material.IRON_ORE,1,4,now).added());
        assertTrue(store.practice(id,Profession.MINER).isEmpty());
        assertEquals(12,service.awardSource(id,Profession.FORESTER,"harvest",Material.OAK_LOG,4,3,now).added());
        assertEquals(4,store.practice(id,Profession.FORESTER).get("harvest:oak_log"));
        service.grantByAdmin(id,"miner",10000);assertTrue(store.practice(id,Profession.MINER).isEmpty());
    }
    @Test void fullDailyCapDoesNotGeneratePhantomPractice(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);store.setActive(id,"farmer",now);
        service.award(id,Profession.FARMER,1200,now);
        assertEquals(0,service.awardSource(id,Profession.FARMER,"harvest",Material.WHEAT,64,3,now).added());
        assertTrue(store.practice(id,Profession.FARMER).isEmpty());
    }
    @Test void trialRequiresXpUnitsDiversityAndActualWorkshopInOrder(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);store.setActive(id,"forester",now);
        store.addXp(id,Profession.FORESTER,1_000_000);var trials=service.trials();
        assertFalse(trials.claim(id,Profession.FORESTER,0));
        store.practice(id,Profession.FORESTER,"harvest:oak_log",100);
        assertFalse(trials.claim(id,Profession.FORESTER,0));
        store.practice(id,Profession.FORESTER,"harvest:birch_log",1);
        assertFalse(trials.claim(id,Profession.FORESTER,0));
        store.completeWork(id,Profession.FORESTER,now+120000,"first");
        assertTrue(trials.claim(id,Profession.FORESTER,0));assertFalse(trials.claim(id,Profession.FORESTER,0));
        assertFalse(trials.claim(id,Profession.FORESTER,2));assertEquals(1,trials.rank(id,Profession.FORESTER));
        assertEquals(24,trials.batch(id,ProfessionMastery.SAWYER));assertEquals(108,trials.cooldown(id,ProfessionMastery.SAWYER));
        store.setActive(id,"miner",now);assertFalse(trials.claim(id,Profession.FORESTER,1));
    }
    @Test void workReceiptAndTrialsSurviveRoundtripWithoutDoubleCounting(){
        var store=store();store.setActive(id,"fisher",now);store.completeWork(id,Profession.FISHER,now+180000,"receipt");
        store.completeWork(id,Profession.FISHER,now+180000,"receipt");assertEquals(1,store.practice(id,Profession.FISHER).get("work"));
        store.claimTrial(id,Profession.FISHER,0);assertTrue(store.flushDurably());
        var loaded=store();loaded.load();assertEquals(1,loaded.trials(id,Profession.FISHER));
        assertEquals("receipt",loaded.receipt(id));assertEquals(now+180000,loaded.workUntil(id,Profession.FISHER));
        loaded.completeWork(id,Profession.FISHER,now+180000,"receipt");assertEquals(1,loaded.practice(id,Profession.FISHER).get("work"));
    }
    @Test void transformedInputsCannotFarmFreeDecorativeCycles(){
        assertTrue(ProfessionCatalog.cutInput(Material.STONE));assertTrue(ProfessionCatalog.cutInput(Material.QUARTZ_BLOCK));
        for(Material m:new Material[]{Material.QUARTZ_PILLAR,Material.CHISELED_QUARTZ_BLOCK,Material.STONE_BRICK_SLAB,Material.STONE_BRICKS})assertFalse(ProfessionCatalog.cutInput(m));
        assertFalse(ProfessionCatalog.craftInput(Profession.MASON,Material.STONE_BRICK_SLAB));
        assertTrue(ProfessionCatalog.craftInput(Profession.MASON,Material.STONE));
    }
    @Test void newRecipesRespectRatiosFuelAndRepairLimits(){
        assertEquals("OAK_PLANKS",ProfessionWorkRecipes.output(ProfessionMastery.SAWYER,"OAK_LOG"));
        assertEquals("STRIPPED_WARPED_STEM",ProfessionWorkRecipes.output(ProfessionMastery.CARVER,"WARPED_STEM"));
        assertNull(ProfessionWorkRecipes.output(ProfessionMastery.SAWYER,"STRIPPED_OAK_LOG"));
        assertEquals(4,ProfessionWorkRecipes.outputRatio(ProfessionMastery.SAWYER));
        assertEquals(3,ProfessionWorkRecipes.inputRatio(ProfessionMastery.MILLER));
        assertEquals(8,ProfessionWorkRecipes.inputRatio(ProfessionMastery.COMPOSTER));
        assertEquals("IRON_INGOT",ProfessionWorkRecipes.output(ProfessionMastery.SMELTER,"RAW_IRON"));
        assertTrue(ProfessionWorkRecipes.needsFuel(ProfessionMastery.SMELTER));
        assertFalse(ProfessionWorkRecipes.needsFuel(ProfessionMastery.MILLER));
        assertEquals("GRAVEL",ProfessionWorkRecipes.output(ProfessionMastery.CRUSHER,"COBBLESTONE"));
        assertEquals("BREAD",ProfessionWorkRecipes.output(ProfessionMastery.MILLER,"WHEAT"));
        assertEquals("BONE_MEAL",ProfessionWorkRecipes.output(ProfessionMastery.COMPOSTER,"WHEAT_SEEDS"));
        assertEquals("COOKED_SALMON",ProfessionWorkRecipes.output(ProfessionMastery.FISH_SMOKER,"SALMON"));
        assertEquals(15,ProfessionMastery.TACKLE_KEEPER.repairPercent());
    }
    @Test void allProfessionsHaveSourcesAndTwoDistinctWorkingSpecializations(){
        assertEquals(8,Profession.values().length);assertEquals(16,ProfessionMastery.values().length);
        for(var profession:Profession.values()){
            assertFalse(ProfessionCatalog.sources(profession).isEmpty());
            assertEquals(2,ProfessionMastery.of(profession).size());
            assertEquals(ProfessionCatalog.sources(profession).size(),new HashSet<>(ProfessionCatalog.sources(profession)).size());
        }
        assertEquals(8,ProfessionCatalog.fishXp(Material.COD));assertEquals(20,ProfessionCatalog.fishXp(Material.ENCHANTED_BOOK));assertEquals(0,ProfessionCatalog.fishXp(Material.ROTTEN_FLESH));
    }
    @Test void futureOrNullSchemaCannotBeOverwritten()throws Exception{
        for(String text:List.of("null","{\"version\":99,\"players\":{}}")){
            Files.writeString(folder.resolve("professions.json"),text);var store=store();assertThrows(IllegalStateException.class,store::load);
            store.addXp(id,Profession.SMITH,10);assertFalse(store.flushDurably());assertEquals(text,Files.readString(store.file()));
        }
    }
    @Test void durableFileReplacesAtomicallyAndReportsFailedWrites()throws Exception{
        Path file=folder.resolve("commit.json");assertTrue(ProfessionDurableFile.write(file,"first",null));
        assertTrue(ProfessionDurableFile.write(file,"second",null));assertEquals("second",Files.readString(file));
        assertFalse(ProfessionDurableFile.write(file.resolve("impossible.json"),"bad",null));
        assertEquals("second",Files.readString(file));ProfessionDurableFile.delete(file);assertFalse(Files.exists(file));
    }
    @Test void failedDurableProgressWriteStaysDirty()throws Exception{
        Files.writeString(folder.resolve("professions.json"),"occupied");
        var store=new ProfessionStore(folder.resolve("professions.json"),null);store.addXp(id,Profession.SMITH,1);
        assertFalse(store.flushDurably());assertTrue(store.isDirty());
    }

    @Test void legacyFullXpBankStillEarnsPracticeButCannotBypassDailyCap(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);
        store.setActive(id,"forester",now);store.addXp(id,Profession.FORESTER,ProfessionStore.MAX_XP);
        assertEquals(0,service.awardSource(id,Profession.FORESTER,"harvest",Material.OAK_LOG,1,3,now).added());
        assertEquals(1,store.practice(id,Profession.FORESTER).get("harvest:oak_log"));
        assertEquals(1197,service.dailyLeft(id,Profession.FORESTER,now));
        service.award(id,Profession.FORESTER,1197,now);
        service.awardSource(id,Profession.FORESTER,"harvest",Material.OAK_LOG,1,3,now);
        assertEquals(1,store.practice(id,Profession.FORESTER).get("harvest:oak_log"));
    }
    @Test void rankFiveKeepsItsHistoricalPerksButNotExtraProgressionLevels(){
        var store=store();var service=new ProfessionService(ProfessionConfig.defaults(),store);
        service.setRankBonus(ignored->new ru.cherdakmd.chrdkregister.api.RankBonus(5,50,25,10));
        assertEquals(100,service.maxLevel(id));assertEquals(35,service.powerCap(id));
        for(var profession:Profession.values())store.addXp(id,profession,1_000_000);
        store.setActive(id,"smith",now);assertEquals(60,service.smithDiscount(id));
        store.setActive(id,"alchemist",now);assertEquals(30,service.alchemistChance(id));
        store.setActive(id,"mason",now);assertEquals(25,service.masonChance(id));
        store.setActive(id,"cook",now);assertEquals(3,service.cookSaturation(id));
    }

}
