package ru.cherdakmd.chrdkenchants;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BookVisualPolicyTest {
    @Test void everySingleCustomAndVanillaEnchantmentGetsItsOwnKey() {
        var keys = new HashSet<NamespacedKey>();
        for (var def : EnchantRegistry.all()) {
            var key = BookVisualPolicy.select(Map.of(def.id(), 1), Map.of());
            assertEquals("chrdk_enchants", key.getNamespace());
            assertEquals("book/custom/" + def.id(), key.getKey());
            assertTrue(keys.add(key));
        }
        var legacy = BookVisualPolicy.select(Map.of("lavawalker", 1), Map.of());
        assertEquals("book/custom/lavawalker", legacy.getKey()); assertTrue(keys.add(legacy));
        for (var id : BookVisualPolicy.VANILLA) {
            var key = BookVisualPolicy.select(Map.of(), Map.of("minecraft:" + id, 1));
            assertEquals("book/vanilla/" + id, key.getKey()); assertTrue(keys.add(key));
        }
        assertEquals(76 + 43, keys.size());
    }

    @Test void mixedCombinationsHaveStableMeaningIndependentOfOrdering() {
        var custom = new HashMap<String, Integer>(); custom.put("lifesteal", 2); custom.put("thunderstrike", 1);
        var vanilla = new HashMap<String, Integer>(); vanilla.put("minecraft:sharpness", 3); vanilla.put("minecraft:unbreaking", 2);
        assertEquals("book/mixed/custom", BookVisualPolicy.select(custom, Map.of()).getKey());
        assertEquals("book/mixed/vanilla", BookVisualPolicy.select(Map.of(), vanilla).getKey());
        assertEquals("book/mixed/both", BookVisualPolicy.select(custom, vanilla).getKey());
        assertEquals("book/mixed/both", BookVisualPolicy.select(Map.of("thunderstrike", 1), Map.of("minecraft:sharpness", 3)).getKey());
        assertEquals(Map.of("lifesteal", 2, "thunderstrike", 1), custom);
        assertEquals(Map.of("minecraft:sharpness", 3, "minecraft:unbreaking", 2), vanilla);
    }

    @Test void emptyMalformedUnknownAndForeignKeysAreNeverReinterpreted() {
        assertNull(BookVisualPolicy.select(Map.of(), Map.of()));
        assertNull(BookVisualPolicy.select(null, Map.of())); assertNull(BookVisualPolicy.select(Map.of(), null));
        assertNull(BookVisualPolicy.select(Map.of("unknown", 1), Map.of()));
        assertNull(BookVisualPolicy.select(Map.of("THUNDERSTRIKE", 1), Map.of()));
        assertNull(BookVisualPolicy.select(Map.of("lifesteal", 0), Map.of()));
        assertNull(BookVisualPolicy.select(Map.of("lifesteal", 100), Map.of()));
        assertNull(BookVisualPolicy.select(Map.of(), Map.of("minecraft:unbreaking", 0)));
        assertNull(BookVisualPolicy.select(Map.of(), Map.of("minecraft:unbreaking", 256)));
        assertNull(BookVisualPolicy.select(Map.of(), Map.of("other:sharpness", 1)));
        assertNull(BookVisualPolicy.select(Map.of("lifesteal", 1), Map.of("minecraft:future_enchantment", 1)));
        var invalid = new HashMap<String, Integer>(); invalid.put("minecraft:sharpness", null);
        assertNull(BookVisualPolicy.select(Map.of(), invalid));
        invalid.clear(); invalid.put(null, 1); assertNull(BookVisualPolicy.select(Map.of(), invalid));
    }
}
