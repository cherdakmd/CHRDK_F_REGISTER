package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class RetiredLavawalkerTest {
    @Test void catalogAndNewSourcesNeverOfferRetiredEnchantEvenIfOldConfigEnablesIt() {
        var config=new YamlConfiguration();config.set("enchants.lavawalker.enabled",true);
        var parsed=EnchantsConfig.parse(config,Logger.getAnonymousLogger());
        assertFalse(parsed.isEnabled("lavawalker"));
        assertFalse(EnchantRegistry.all().stream().anyMatch(def->def.id().equals("lavawalker")));
        assertFalse(parsed.enabledLight().stream().anyMatch(def->def.id().equals("lavawalker")));
        var old=EnchantRegistry.byId("lavawalker");assertNotNull(old);
        assertTrue(EnchantRoller.applicable(List.of(old),"DIAMOND_BOOTS",Map.of(),3,1).isEmpty());
    }
    @Test void legacyDataSurvivesAndDoesNotConsumeActiveOrLegendaryLimits() {
        var original=Map.of("lavawalker",1,"swiftfeet",2);
        assertEquals(original,EnchantBookValidation.read(EnchantCodec.encode(original)));
        assertEquals(original,EnchantLibrary.read(EnchantLibrary.encode(original)));
        var all=new LinkedHashMap<String,Integer>();
        EnchantRegistry.all().forEach(def->all.put(def.id(),1));all.put("lavawalker",1);
        assertEquals(76,all.size());assertEquals(all,EnchantBookValidation.read(EnchantCodec.encode(all)));
        var phoenix=EnchantRegistry.byId("phoenix");
        assertTrue(EnchantRoller.applicable(List.of(phoenix),null,Map.of("lavawalker",1),1,1).contains(phoenix));
        var plan=PartialBookPlan.build(null,true,Map.of("lavawalker",1),Map.of("phoenix",1),id->true,1,1);
        assertNotNull(plan);assertEquals(Map.of("phoenix",1),plan.transferred());
        assertEquals(Map.of("lavawalker",1,"phoenix",1),plan.result());
        var retired=PartialBookPlan.build(null,true,Map.of(),Map.of("lavawalker",1),id->true,1,1);
        assertEquals(PartialBookPlan.Reason.DISABLED,retired.reasons().get("lavawalker"));
        assertEquals(Map.of("lavawalker",1),retired.remaining());
    }
    @Test void directCreationIsBlockedAndLibraryHidesRatherThanDeletesHistory() throws Exception {
        String items=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantItems.java"));
        assertTrue(items.contains("item == null || def == null || EnchantRegistry.retired(def.id())"));
        assertTrue(items.contains("def == null || EnchantRegistry.retired(def.id())"));
        String library=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantLibraryListener.java"));
        assertTrue(library.contains("filter(entry -> !EnchantRegistry.retired(entry.getKey()))"));
    }
}
