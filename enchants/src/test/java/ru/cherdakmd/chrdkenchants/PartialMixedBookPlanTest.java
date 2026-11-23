package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class PartialMixedBookPlanTest {
    private PartialMixedBookPlan.Plan<String> plan(Map<String,Integer> custom, Map<String,Integer> vanilla) {
        return PartialMixedBookPlan.build("DIAMOND_SWORD",false,Map.of(),custom,id->true,4,1,
                Map.of(),vanilla,Function.identity(),id->!id.equals("fortune"),
                (a,b)->a.equals("silk")&&b.equals("fortune"),id->10);
    }
    @Test void bothLayersArePartitionedIndependently() {
        var p=plan(Map.of("lifesteal",2,"seer",1),Map.of("sharpness",3,"fortune",2));
        assertNotNull(p);assertTrue(p.partial());
        assertEquals(Map.of("lifesteal",2),p.custom().transferred());
        assertEquals(Map.of("seer",1),p.custom().remaining());
        assertEquals(Map.of("sharpness",3),p.vanilla().transferred());
        assertEquals(Map.of("fortune",2),p.vanilla().remaining());
    }
    @Test void fullyCompatibleAndFullyRejectedAreNotPartial() {
        var full=plan(Map.of("lifesteal",1),Map.of("sharpness",1));
        assertTrue(full.transfersAnything());assertFalse(full.hasRemainder());assertFalse(full.partial());
        var none=plan(Map.of("seer",1),Map.of("fortune",1));
        assertFalse(none.transfersAnything());assertTrue(none.hasRemainder());assertFalse(none.partial());
    }
    @Test void oneLayerCanBeEmptyOrEntirelyRemain() {
        var customOnly=plan(Map.of("lifesteal",1,"seer",1),Map.of());
        assertTrue(customOnly.partial());assertNull(customOnly.vanilla());
        var vanillaRemains=plan(Map.of("lifesteal",1),Map.of("fortune",1));
        assertTrue(vanillaRemains.partial());
        var customRemains=plan(Map.of("seer",1),Map.of("sharpness",1));
        assertTrue(customRemains.partial());
    }
    @Test void invalidEitherLayerRejectsWholePlan() {
        assertNull(plan(Map.of("unknown",1),Map.of("sharpness",1)));
        assertNull(plan(Map.of("lifesteal",1),Map.of("fortune",1,"silk",1)));
        assertNull(plan(Map.of("lifesteal",1),Map.of("sharpness",0)));
        assertNull(plan(Map.of(),Map.of("sharpness",1)));
    }
}
