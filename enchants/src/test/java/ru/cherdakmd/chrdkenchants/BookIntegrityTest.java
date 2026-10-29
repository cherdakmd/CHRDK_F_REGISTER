package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BookIntegrityTest {
    @Test void allNativeDefinitionsRoundTripAtTheirMaximum() {
        for (var def : EnchantRegistry.all()) assertEquals(Map.of(def.id(), def.maxLevel()), EnchantBookValidation.read(def.id()+":"+def.maxLevel()));
    }
    @Test void anyBadTokenRejectsTheWholeBookInsteadOfPartialTransfer() {
        for (String raw : List.of("", "seer:1;unknown:1", "seer:1;scholar:0", "seer:1;scholar:99999999999999", "seer:1;broken",
                "seer:1;seer:1", "seer:1;", "seer:1;scholar:abc", "seer:1;air_reserve:3", "x".repeat(4097))) assertNull(EnchantBookValidation.read(raw), raw);
        assertEquals(Map.of("seer", 1, "scholar", 1), EnchantBookValidation.read("seer:1;scholar:1"));
    }
    @Test void originalTolerantCodecWasNotSilentlyChangedForOldEffects() {
        assertEquals(Map.of("seer",1), EnchantCodec.decode("seer:1;broken"));
        assertNull(EnchantBookValidation.read("seer:1;broken"));
    }
    @Test void unusualFinalVanillaResultsFailClosed() {
        assertFalse(TableListener.validVanilla(Map.of())); assertFalse(TableListener.validVanilla(Map.of("test",0)));
        assertFalse(TableListener.validVanilla(Map.of("test",256))); assertTrue(TableListener.validVanilla(Map.of("test",5)));
        Map<String,Integer> invalid = new HashMap<>(); invalid.put(null, 1); assertFalse(TableListener.validVanilla(invalid));
        invalid.clear(); invalid.put("test", null); assertFalse(TableListener.validVanilla(invalid));
    }
    @Test void anvilGuardsBothPreviewAndTakeAndTableQueueIsBoundedAndCleared() throws Exception {
        String service = source("EnchantService"), anvil = source("AnvilApplyListener"), table = source("TableListener");
        assertTrue(service.contains("items.validated(book) == null || items.validated(item) == null"));
        assertTrue(service.contains("throw new IllegalArgumentException(\"Book transfer rejected\")"));
        assertTrue(anvil.contains("plugin.items().marked(right)")); assertTrue(anvil.contains("event.setCancelled(true)"));
        assertTrue(table.contains("pending.size() >= LIMIT")); assertTrue(table.contains("pending.remove(player.getUniqueId(), ticket)"));
        assertTrue(table.contains("LIMIT = 4096")); assertTrue(source("EnchantsPlugin").contains("if (table != null) table.clear()"));
    }
    @Test void sourcePercentagesUseSameCapsAsRuntimeAndNeverShowNan() {
        assertEquals(1, EnchantSourceReport.chance(100, .35, 1)); assertEquals(25, EnchantSourceReport.chance(100, 8, 25));
        assertEquals(.35, EnchantSourceReport.chance(Double.NaN, .35, 1)); assertEquals(0, EnchantSourceReport.chance(-1, 8,25));
        assertEquals("11.67", EnchantSourceReport.percent(35/3.0)); assertEquals("—", EnchantSourceReport.percent(Double.NaN));
    }
    private String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/"+name+".java")); }
}
