package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EnchantMutationPolicyTest {
    @Test void strictDataMustNotBeReplacedByATolerantPartialDecode() {
        var seer=EnchantRegistry.byId("seer");
        assertTrue(EnchantMutationPolicy.allowed(Map.of(),seer));
        assertTrue(EnchantMutationPolicy.allowed(Map.of("seer",1),seer));
        var invalid=EnchantBookValidation.read("seer:1;broken");
        assertNull(invalid);assertFalse(EnchantMutationPolicy.allowed(invalid,seer));
        assertEquals(Map.of("seer",1),EnchantCodec.decode("seer:1;broken"));
    }
    @Test void unknownForgedRetiredAndConflictingSetsNeverPermitAWrite() {
        var thunder=EnchantRegistry.byId("thunderstrike");
        var chain=EnchantRegistry.byId("chainspark");
        assertFalse(EnchantMutationPolicy.allowed(Map.of("thunderstrike",1),chain));
        assertFalse(EnchantMutationPolicy.allowed(Map.of("thunderstrike",1,"chainspark",1),thunder));
        assertFalse(EnchantMutationPolicy.allowed(Map.of(),EnchantRegistry.byId("lavawalker")));
        assertFalse(EnchantMutationPolicy.allowed(Map.of(),null));
        var forged=new EnchantDef("seer",EnchantDef.Rarity.LEGENDARY,100,null,Set.of(ItemGroup.SWORD),EnchantDef.Polarity.LIGHT);
        assertFalse(EnchantMutationPolicy.allowed(Map.of(),forged));
    }
    @Test void productionAppliesOnlyTheStrictlyDecodedMap() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantItems.java"));
        int start=source.indexOf("public boolean apply(ItemStack");
        int end=source.indexOf("public ItemStack makeEnchantBook(",start);
        assertTrue(start>=0 && end>start);
        String apply=source.substring(start,end);
        assertTrue(apply.contains("validated(item)"));
        assertTrue(apply.contains("EnchantMutationPolicy.allowed(validated, def)"));
        assertTrue(apply.contains("new LinkedHashMap<>(validated)"));
        assertFalse(apply.contains("new LinkedHashMap<>(read(item))"));
    }
}
