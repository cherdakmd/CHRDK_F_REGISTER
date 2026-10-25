package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Фильтр по вкладкам, поиск и раскладка витрины — чистые правила без Bukkit. */
class ShopCatalogTest {

    private static ShopConfig.ShopItem item(String material, ShopCategory category) {
        return new ShopConfig.ShopItem(material, category.id(), 1.0, 1000, 0.25, 4.0);
    }

    @Test
    void filterKeepsOnlyRequestedCategory() {
        List<ShopConfig.ShopItem> items = List.of(
                item("STONE", ShopCategory.BLOCKS),
                item("IRON_INGOT", ShopCategory.MINERALS),
                item("OAK_LOG", ShopCategory.WOOD),
                item("DIRT", ShopCategory.BLOCKS));
        assertEquals(4, ShopCatalog.filter(items, ShopCategory.ALL).size());
        assertEquals(List.of("STONE", "DIRT"), ShopCatalog.filter(items, ShopCategory.BLOCKS)
                .stream().map(ShopConfig.ShopItem::material).toList());
        assertTrue(ShopCatalog.filter(items, ShopCategory.FOOD).isEmpty());
        assertTrue(ShopCatalog.filter(List.of(), ShopCategory.ALL).isEmpty());
    }

    @Test
    void tabsSkipEmptyCategoriesAndStartWithAll() {
        List<ShopConfig.ShopItem> items = List.of(
                item("STONE", ShopCategory.BLOCKS),
                item("IRON_INGOT", ShopCategory.MINERALS),
                item("DIRT", ShopCategory.BLOCKS));
        assertEquals(List.of(ShopCategory.ALL, ShopCategory.BLOCKS, ShopCategory.MINERALS),
                ShopCatalog.tabs(items));
        assertEquals(List.of(ShopCategory.ALL), ShopCatalog.tabs(List.of()));
        assertEquals(List.of(ShopCategory.ALL), ShopCatalog.tabs(null));
    }

    @Test
    void searchFindsFragmentCaseInsensitively() {
        List<ShopConfig.ShopItem> items = List.of(
                item("IRON_INGOT", ShopCategory.MINERALS),
                item("IRON_BLOCK", ShopCategory.MINERALS),
                item("OAK_LOG", ShopCategory.WOOD),
                item("GOLDEN_APPLE", ShopCategory.FOOD));
        assertEquals(2, ShopCatalog.search(items, "iron").size());
        assertEquals(2, ShopCatalog.search(items, "IRON").size());
        assertEquals(1, ShopCatalog.search(items, "apple").size());
        assertTrue(ShopCatalog.search(items, "  ").isEmpty(), "пустой запрос не показывает всё");
        assertTrue(ShopCatalog.search(items, "diamond").isEmpty(), "валюту не ищем: её нет в каталоге");
    }

    @Test
    void layoutMapsSlotsAndPages() {
        assertEquals(1, ShopLayout.pages(0));
        assertEquals(1, ShopLayout.pages(1));
        assertEquals(1, ShopLayout.pages(36));
        assertEquals(2, ShopLayout.pages(37));
        assertEquals(4, ShopLayout.pages(142));

        assertEquals(0, ShopLayout.clampPage(-5, 100));
        assertEquals(2, ShopLayout.clampPage(99, 100));
        assertEquals(3, ShopLayout.clampPage(3, 142));

        assertEquals(0, ShopLayout.itemIndex(0, ShopLayout.FIRST_ITEM_SLOT));
        assertEquals(35, ShopLayout.itemIndex(0, ShopLayout.LAST_ITEM_SLOT));
        assertEquals(36, ShopLayout.itemIndex(1, ShopLayout.FIRST_ITEM_SLOT));
        assertEquals(-1, ShopLayout.itemIndex(0, ShopLayout.SLOT_CLOSE));
        assertEquals(-1, ShopLayout.itemIndex(0, ShopLayout.SLOT_PREV));
        assertEquals(-1, ShopLayout.itemIndex(0, ShopLayout.SLOT_NEXT));

        assertTrue(ShopLayout.isTabSlot(0));
        assertTrue(ShopLayout.isTabSlot(ShopLayout.MAX_TABS - 1));
        assertFalse(ShopLayout.isTabSlot(ShopLayout.SLOT_CLOSE));
    }
}
