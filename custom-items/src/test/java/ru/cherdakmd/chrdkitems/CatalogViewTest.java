package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogViewTest {
    private ItemCatalog.Definition item(String id, ItemCatalog.Category category) {
        return new ItemCatalog.Definition(id, category, Material.IRON_SWORD, "chrdk_items:item/" + id);
    }
    @Test void filteringPaginationAndDeterministicOrder() {
        var entries = new LinkedHashMap<String, ItemCatalog.Definition>();
        for (int i = 12; i >= 0; i--) {
            String id = "item_" + String.format("%02d", i);
            entries.put(id, item(id, i == 0 ? ItemCatalog.Category.TOOL : ItemCatalog.Category.WEAPON));
        }
        var first = CatalogView.search(entries, CatalogView.parse(new String[]{"WEAPON"}));
        assertEquals(12, first.total()); assertEquals(2, first.pages());
        assertEquals("item_01", first.items().getFirst().id()); assertEquals(8, first.items().size());
        var second = CatalogView.search(entries, CatalogView.parse(new String[]{"weapon", "2"}));
        assertEquals(4, second.items().size()); assertEquals("item_09", second.items().getFirst().id());
        assertTrue(CatalogView.search(entries, CatalogView.parse(new String[]{"weapon", "3"})).items().isEmpty());
        assertEquals(1, CatalogView.search(Map.of(), CatalogView.parse(new String[]{})).pages());
        assertEquals(1, CatalogView.search(entries, CatalogView.parse(new String[]{"tool"})).total());
        assertThrows(UnsupportedOperationException.class, () -> first.items().clear());
    }
    @Test void malformedRequestsNeverReachPageCalculation() {
        for (String[] input : new String[][]{{"wat"}, {"all", "0"}, {"all", "-1"}, {"all", "2147483647"},
                {"all", "9999999"}, {"all", "foo"}, {"all", "1", "extra"}})
            assertThrows(IllegalArgumentException.class, () -> CatalogView.parse(input));
    }
}
