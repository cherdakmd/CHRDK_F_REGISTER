package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogTest {
    private static java.util.Map<String, ItemCatalog.Definition> load(String yaml) {
        return ItemCatalog.load(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)));
    }
    private static String definition(String id, String category, String material, String model) {
        return "items:\n  " + id + ":\n    category: " + category + "\n    material: " + material
                + "\n    model: " + model + "\n";
    }
    @Test void emptyAndValidCatalogs() {
        assertTrue(load("items: {}\n").isEmpty());
        var entries = load(definition("blade", "weapon", "IRON_SWORD", "chrdk_items:item/blade"));
        assertEquals(Material.IRON_SWORD, entries.get("blade").material());
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertEquals(ItemCatalog.Category.ARMOR, load(definition("helm", "armor", "IRON_HELMET", "chrdk_items:item/helm")).get("helm").category());
        assertEquals(ItemCatalog.Category.TOOL, load(definition("pick", "tool", "IRON_PICKAXE", "chrdk_items:item/pick")).get("pick").category());
        assertEquals(ItemCatalog.Category.CONSUMABLE, load(definition("scroll", "consumable", "PAPER", "chrdk_items:item/scroll")).get("scroll").category());
    }
    @Test void rejectsUnsafeOrIncompleteDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> load("other: {}"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  test: {}"));
        assertThrows(IllegalArgumentException.class, () -> load(definition("Bad-id", "weapon", "IRON_SWORD", "chrdk_items:item/blade")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("blade", "weapon", "STONE", "chrdk_items:item/blade")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("potion", "consumable", "POTION", "chrdk_items:item/potion")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("blade", "magic", "IRON_SWORD", "chrdk_items:item/blade")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("blade", "weapon", "IRON_SWORD", "minecraft:item/blade")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("blade", "weapon", "IRON_SWORD", "chrdk_items:item/../blade")));
        assertThrows(IllegalArgumentException.class, () -> load(definition("blade", "weapon", "IRON_SWORD", "chrdk_items:item/blade") + "    unknown: true\n"));
    }
}
