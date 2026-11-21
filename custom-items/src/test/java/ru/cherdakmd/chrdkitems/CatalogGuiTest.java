package ru.cherdakmd.chrdkitems;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class CatalogGuiTest {
    @Test void itemSlotsDoNotOverlapNavigation() {
        var slots=new HashSet<Integer>();
        for (int i=0;i<CatalogView.PAGE_SIZE;i++) assertTrue(slots.add(CatalogGui.itemSlot(i)));
        for(int navigation : new int[]{9,10,11,12,13,45,49,53}) assertFalse(slots.contains(navigation));
        assertEquals(10,CatalogGui.categorySlot(ItemCatalog.Category.WEAPON));
        assertEquals(11,CatalogGui.categorySlot(ItemCatalog.Category.ARMOR));
        assertEquals(12,CatalogGui.categorySlot(ItemCatalog.Category.TOOL));
        assertEquals(13,CatalogGui.categorySlot(ItemCatalog.Category.CONSUMABLE));
        assertThrows(IllegalArgumentException.class,()->CatalogGui.itemSlot(CatalogView.PAGE_SIZE));
    }
}
