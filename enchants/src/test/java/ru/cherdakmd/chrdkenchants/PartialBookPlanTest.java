package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PartialBookPlanTest {
    private static PartialBookPlan.Plan plan(String material,Map<String,Integer> target,Map<String,Integer> book,int max,int legendary) {
        return PartialBookPlan.build(material,false,target,book,id->true,max,legendary);
    }
    @Test void mixedEquipmentGetsOnlyCompatiblePartAndPreservesAllOtherLevels() {
        var p=plan("DIAMOND_SWORD",Map.of(),Map.of("lifesteal",2,"seer",1),4,1);
        assertEquals(Map.of("lifesteal",2),p.transferred());assertEquals(Map.of("seer",1),p.remaining());
        assertEquals(Map.of("lifesteal",2),p.result());assertEquals(PartialBookPlan.Reason.MISMATCH,p.reasons().get("seer"));
    }
    @Test void maxedOutAndWeakerCopiesStayInRemainderRatherThanBurning() {
        var p=plan("DIAMOND_SWORD",Map.of("lifesteal",3),Map.of("lifesteal",1,"scholar",1),4,1);
        assertEquals(Map.of("lifesteal",1),p.remaining());assertEquals(PartialBookPlan.Reason.NO_IMPROVEMENT,p.reasons().get("lifesteal"));
        assertEquals(Map.of("scholar",1),p.transferred());assertEquals(Map.of("lifesteal",3,"scholar",1),p.result());
    }
    @Test void equalLevelsUpgradeButExistingCapAndOtherCharLimitsRemainEffective() {
        var p=plan("DIAMOND_SWORD",Map.of("lifesteal",1),Map.of("lifesteal",1,"scholar",1),1,1);
        assertEquals(Map.of("lifesteal",2),p.result());assertEquals(Map.of("lifesteal",1),p.transferred());
        assertEquals(PartialBookPlan.Reason.LIMIT,p.reasons().get("scholar"));
    }
    @Test void conflictsDisabledAndLegendaryLimitsLeaveOriginalBookEntries() {
        var p=plan("DIAMOND_PICKAXE",Map.of("veinminer",1,"midas",1),Map.of("excavator",2,"scholar",2),4,1);
        assertEquals(PartialBookPlan.Reason.CONFLICT,p.reasons().get("excavator"));assertEquals(2,p.remaining().get("excavator"));
        var legendary=plan("DIAMOND_CHESTPLATE",Map.of("laststand",1),Map.of("phoenix",1),4,1);
        assertEquals(PartialBookPlan.Reason.CONFLICT,legendary.reasons().get("phoenix"));
        var disabled=PartialBookPlan.build("DIAMOND_SWORD",false,Map.of(),Map.of("lifesteal",2),id->false,4,1);
        assertEquals(PartialBookPlan.Reason.DISABLED,disabled.reasons().get("lifesteal"));assertTrue(disabled.transferred().isEmpty());
        var cap=plan("DIAMOND_SWORD",Map.of("execution",1),Map.of("scholar",1),4,0);
        assertEquals(Map.of("scholar",1),cap.transferred());
        var denied=plan("DIAMOND_SWORD",Map.of(),Map.of("execution",1),4,0);
        assertEquals(PartialBookPlan.Reason.LEGENDARY_LIMIT,denied.reasons().get("execution"));
    }
    @Test void axesElytraAndCursesUseTheSameDeclaredRules() {
        var axe=plan("DIAMOND_AXE",Map.of(),Map.of("lifesteal",1,"seer",1),4,1);
        assertEquals(Map.of("lifesteal",1),axe.transferred());assertEquals(Map.of("seer",1),axe.remaining());
        var elytra=plan("ELYTRA",Map.of(),Map.of("phoenix",1,"thin_padding",1),4,1);
        assertEquals(2,elytra.transferred().size());
        var conflict=plan("ELYTRA",Map.of("bulwark",1),Map.of("thin_padding",2),4,1);
        assertEquals(PartialBookPlan.Reason.CONFLICT,conflict.reasons().get("thin_padding"));
    }
    @Test void decisionsAreStableRegardlessOfMapOrder() {
        var first=new LinkedHashMap<String,Integer>();first.put("scholar",1);first.put("lifesteal",1);
        var second=new LinkedHashMap<String,Integer>();second.put("lifesteal",1);second.put("scholar",1);
        assertEquals(plan("DIAMOND_SWORD",Map.of(),first,1,1),plan("DIAMOND_SWORD",Map.of(),second,1,1));
        assertEquals(Map.of("lifesteal",1),plan("DIAMOND_SWORD",Map.of(),first,1,1).transferred());
    }
    @Test void booksIgnoreMaterialSupportButKeepConflictsAndLimits() {
        var p=PartialBookPlan.build(null,true,Map.of(),Map.of("lifesteal",1,"seer",1),id->true,4,1);
        assertEquals(2,p.transferred().size());assertTrue(p.remaining().isEmpty());
        assertNull(plan("BOOK",Map.of(),Map.of("lifesteal",1),4,1));
    }
    @Test void invalidConflictingOrDamagedInputsAreNeverPartiallyConsumed() {
        assertNull(plan("DIAMOND_PICKAXE",Map.of(),Map.of("veinminer",1,"excavator",1),4,1));
        assertNull(plan("DIAMOND_PICKAXE",Map.of("veinminer",1,"excavator",1),Map.of("scholar",1),4,1));
        assertNull(plan("DIAMOND_SWORD",Map.of(),Map.of("no_such_id",1),4,1));
        assertNull(plan("DIAMOND_SWORD",Map.of(),Map.of("LIFESTEAL",1),4,1));
        assertNull(plan("DIAMOND_SWORD",Map.of(),Map.of("lifesteal",4),4,1));
        assertNull(plan("DIAMOND_SWORD",Map.of(),Map.of(),4,1));
    }
    @Test void allCatalogueEntriesArePartitionedExactlyForEverySupportedTarget() {
        for(var def:EnchantRegistry.all()) for(String material:List.of("DIAMOND_SWORD","DIAMOND_AXE","DIAMOND_PICKAXE","DIAMOND_BOOTS","ELYTRA","BOW")) {
            var original=Map.of(def.id(),def.maxLevel());var p=plan(material,Map.of(),original,4,1);
            assertNotNull(p,def.id());var combined=new HashMap<>(p.transferred());
            p.remaining().forEach((key,value)->assertNull(combined.put(key,value),"duplicated "+key));
            assertEquals(original,combined,def.id());assertTrue(EnchantConflicts.validSet(p.result()));
            if(def.supports(material))assertEquals(original,p.transferred(),def.id()+" "+material);
            else assertEquals(original,p.remaining(),def.id()+" "+material);
        }
    }
    @Test void everyValidTwoEnchantBookConservesEntriesAcrossLimitsAndTargets() {
        var all=new ArrayList<>(EnchantRegistry.all());
        for(int i=0;i<all.size();i++) for(int j=i+1;j<all.size();j++) {
            var a=all.get(i);var b=all.get(j);
            var book=new LinkedHashMap<String,Integer>();
            book.put(a.id(),a.maxLevel());book.put(b.id(),b.maxLevel());
            if(!EnchantConflicts.validSet(book))continue;
            for(String material:List.of("DIAMOND_SWORD","DIAMOND_PICKAXE","ELYTRA","BOW"))
                for(int max:List.of(0,1,2)) {
                    var p=plan(material,Map.of(),book,max,1);
                    assertNotNull(p,a.id()+"/"+b.id());
                    var combined=new HashMap<>(p.transferred());
                    p.remaining().forEach((id,level)->assertNull(combined.put(id,level),"duplicate "+id));
                    assertEquals(book,combined,"lost book entry");
                    assertEquals(p.remaining().keySet(),p.reasons().keySet());
                    assertEquals(p.transferred(),p.result());
                    assertTrue(p.result().size()<=max);
                    assertTrue(EnchantConflicts.validSet(p.result()));
                    p.transferred().keySet().forEach(id->assertTrue(EnchantRegistry.byId(id).supports(material)));
                    assertEquals(2,book.size(),"planner must not mutate its input");
                }
        }
    }

}
