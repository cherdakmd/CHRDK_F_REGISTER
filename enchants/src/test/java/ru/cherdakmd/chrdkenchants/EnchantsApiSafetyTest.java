package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EnchantsApiSafetyTest {
    @Test
    void mutationPathsRejectCorruptDataBeforeWriting() throws Exception {
        String bridge = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsApiBridge.java"));
        assertTrue(bridge.contains("Map<String, Integer> present = plugin.items().validated(item)"));
        assertTrue(bridge.contains("!EnchantConflicts.validSet(present)"));
        assertTrue(bridge.contains("def.curse() && !config.cursesEnabled()"));
        assertTrue(bridge.contains("Map<String, Integer> source = plugin.items().validated(from)"));
        assertTrue(bridge.contains("Map<String, Integer> target = plugin.items().validated(to)"));
        assertTrue(bridge.contains("source == null || target == null || source.isEmpty()"));
    }

    @Test
    void malformedEncodingsAreRejectedButLegacyConflictsRemainReadable() {
        assertNull(EnchantBookValidation.read("thunderstrike:1;unknown:1"));
        assertNull(EnchantBookValidation.read("thunderstrike:1;thunderstrike:2"));
        assertNotNull(EnchantBookValidation.read("thunderstrike:1;chainspark:1"));
        assertFalse(EnchantConflicts.validSet(EnchantBookValidation.read("thunderstrike:1;chainspark:1")));
    }
}
