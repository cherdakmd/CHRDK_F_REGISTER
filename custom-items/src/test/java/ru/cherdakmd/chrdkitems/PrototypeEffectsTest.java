package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeEffectsTest {
    @Test void everyExampleHasItsOwnEffectAndOnlyConsumablesAreSpent() throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(new File("examples/catalog.yml"));
        var examples = ItemCatalog.load(yaml);
        var seen = new HashSet<PrototypeEffects.Effect>();
        for (var def : examples.values()) {
            var effect = PrototypeEffects.effect(def);
            assertNotNull(effect, def.id()); assertTrue(seen.add(effect), def.id());
            assertEquals(def.category() == ItemCatalog.Category.CONSUMABLE, effect.consumable, def.id());
            var plan = PrototypeUse.plan(effect, 5, "результат");
            assertEquals(effect.consumable ? 4 : 5, plan.remaining());
            assertEquals(effect.consumable, plan.consume());
            assertNull(PrototypeUse.plan(effect, 0, "результат"));
            assertNull(PrototypeUse.plan(effect, 1, null));
            assertNull(PrototypeUse.plan(effect, 1, "  "));
            if (effect.consumable) assertEquals(0, PrototypeUse.plan(effect, 1, "результат").remaining());
        }
        assertEquals(12, seen.size());
        assertNull(PrototypeEffects.effect(null));
        assertNull(PrototypeEffects.effect(new ItemCatalog.Definition("test_scroll", ItemCatalog.Category.CONSUMABLE,
                Material.ECHO_SHARD, "chrdk_items:item/test_scroll")));
        assertNull(PrototypeEffects.effect(new ItemCatalog.Definition("test_scroll", ItemCatalog.Category.CONSUMABLE,
                Material.PAPER, "chrdk_items:item/other")));
        assertNull(PrototypeUse.plan(null, 1, "результат"));
    }
}
