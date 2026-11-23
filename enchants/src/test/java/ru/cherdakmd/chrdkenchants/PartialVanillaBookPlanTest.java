package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class PartialVanillaBookPlanTest {
    static final BiPredicate<String,String> CONFLICT=(a,b)->a.equals("silk")&&b.equals("fortune");
    private static PartialVanillaBookPlan.Plan<String> plan(Map<String,Integer> target,Map<String,Integer> book,Predicate<String> allowed) {
        return PartialVanillaBookPlan.build(target,book,Function.identity(),allowed,CONFLICT,id->10);
    }
    @Test void applicableAndUnchangedLevelsPartitionWithoutLoss() {
        var p=plan(Map.of("sharpness",4),Map.of("sharpness",2,"mending",1,"fortune",3),id->!id.equals("fortune"));
        assertEquals(Map.of("mending",1),p.transferred());
        assertEquals(Map.of("sharpness",2,"fortune",3),p.remaining());
        assertEquals(Map.of("sharpness",4,"mending",1),p.result());
        assertEquals(PartialVanillaBookPlan.Reason.NO_IMPROVEMENT,p.reasons().get("sharpness"));
        assertEquals(PartialVanillaBookPlan.Reason.MISMATCH,p.reasons().get("fortune"));
    }
    @Test void asymmetricRegistryConflictMustRejectEitherDirection() {
        var p=plan(Map.of("fortune",3),Map.of("silk",1,"mending",1),id->true);
        assertEquals(PartialVanillaBookPlan.Reason.CONFLICT,p.reasons().get("silk"));
        assertEquals(Map.of("mending",1),p.transferred());assertEquals(Map.of("silk",1),p.remaining());
        assertNull(plan(Map.of(),Map.of("fortune",3,"silk",1),id->true));
    }
    @Test void overVanillaLevelsNeverReducedAndEqualLevelsUseConfiguredCap() {
        var p=plan(Map.of("efficiency",8),Map.of("efficiency",8),id->true);
        assertEquals(9,p.result().get("efficiency"));
        var above=plan(Map.of("efficiency",15),Map.of("efficiency",15),id->true);
        assertEquals(Map.of("efficiency",15),above.result());assertEquals(Map.of("efficiency",15),above.remaining());
    }
    @Test void deterministicUnderDifferentMapOrdersAndInvalidInputFailsClosed() {
        var first=new LinkedHashMap<String,Integer>();first.put("sharpness",1);first.put("mending",1);
        var second=new LinkedHashMap<String,Integer>();second.put("mending",1);second.put("sharpness",1);
        assertEquals(plan(Map.of(),first,id->true),plan(Map.of(),second,id->true));
        assertNull(plan(Map.of(),Map.of("mending",0),id->true));
        assertNull(plan(Map.of("fortune",1,"silk",1),Map.of("mending",1),id->true));
        assertNull(plan(Map.of(),Map.of(),id->true));
    }
    @Test void registryKeyAliasesAreNotSilentlyMerged() {
        record E(String key,int n) { }
        var a=new E("same",1);var b=new E("same",2);
        assertNull(PartialVanillaBookPlan.build(Map.of(a,1),Map.of(b,1),E::key,x->true,(x,y)->false,x->5));
    }
}
