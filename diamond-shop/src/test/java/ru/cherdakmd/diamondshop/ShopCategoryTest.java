package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разделы каталога: идентификаторы, ключи сообщений и иконки вкладок. */
class ShopCategoryTest {

    @Test
    void idsAndMessageKeysAreUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (ShopCategory category : ShopCategory.values()) {
            assertTrue(ids.add(category.id()), "повтор id: " + category.id());
            assertTrue(keys.add(category.messageKey()), "повтор ключа: " + category.messageKey());
            assertEquals(category.id().toLowerCase(), category.id(), "id только в нижнем регистре");
            assertFalse(category.icon().isBlank(), "у вкладки должна быть иконка");
        }
        assertEquals(8, ShopCategory.values().length, "7 разделов + «Все»");
    }

    @Test
    void byIdIsCaseInsensitiveAndSafe() {
        assertEquals(ShopCategory.BLOCKS, ShopCategory.byId("blocks").orElseThrow());
        assertEquals(ShopCategory.BLOCKS, ShopCategory.byId("  BLOCKS ").orElseThrow());
        assertEquals(ShopCategory.MINERALS, ShopCategory.byId("Minerals").orElseThrow());
        assertTrue(ShopCategory.byId("no-such-category").isEmpty());
        assertTrue(ShopCategory.byId(null).isEmpty());
        assertTrue(ShopCategory.byId("").isEmpty());
    }

    @Test
    void defaultCategoryIsMisc() {
        assertEquals(ShopCategory.MISC, ShopCategory.DEFAULT);
        assertTrue(ShopCategory.byId("misc").isPresent());
    }
}
