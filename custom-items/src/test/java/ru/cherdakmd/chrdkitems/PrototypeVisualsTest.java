package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeVisualsTest {
    @Test void exampleCatalogMapsEveryPrototype() throws Exception {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.load(new java.io.File("examples/catalog.yml"));
        var examples = ItemCatalog.load(yaml);
        assertEquals(12, examples.size());
        for (var category : ItemCatalog.Category.values())
            assertEquals(3, examples.values().stream().filter(def -> def.category() == category).count(), category.name());
        for (var definition : examples.values()) assertEquals(810001, PrototypeVisuals.modelData(definition));
    }
    @Test void onlyExactPrototypeDefinitionsReceiveModelData() {
        var good = new ItemCatalog.Definition("test_scroll", ItemCatalog.Category.CONSUMABLE,
                Material.PAPER, "chrdk_items:item/test_scroll");
        assertEquals(810001, PrototypeVisuals.modelData(good));
        assertNull(PrototypeVisuals.modelData(null));
        assertNull(PrototypeVisuals.modelData(new ItemCatalog.Definition("test_scroll", ItemCatalog.Category.CONSUMABLE,
                Material.ECHO_SHARD, "chrdk_items:item/test_scroll")));
        assertNull(PrototypeVisuals.modelData(new ItemCatalog.Definition("test_scroll", ItemCatalog.Category.CONSUMABLE,
                Material.PAPER, "chrdk_items:item/other")));
        assertNull(PrototypeVisuals.modelData(new ItemCatalog.Definition("other", ItemCatalog.Category.CONSUMABLE,
                Material.PAPER, "chrdk_items:item/other")));
    }
}
