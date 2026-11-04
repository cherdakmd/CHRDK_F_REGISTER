package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;
import ru.cherdakmd.chrdkregister.api.ShopExchange;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ключ книги каталога и его связь с замороженной сделкой ядра. */
class ShopBookKeyTest {

    @Test
    void keyRoundTripPreservesIdLevelAndKind() {
        var custom = new ShopBookKey.Spec("veinminer", 3, true);
        var vanilla = new ShopBookKey.Spec("protection", 4, false);
        assertEquals("BOOK_CUSTOM_VEINMINER_3", custom.key());
        assertEquals("BOOK_VANILLA_PROTECTION_4", vanilla.key());
        assertEquals(custom, ShopBookKey.fromKey(custom.key()));
        assertEquals(vanilla, ShopBookKey.fromKey(vanilla.key()));
        assertEquals("books-custom", custom.category());
        assertEquals("books-vanilla", vanilla.category());
    }

    @Test
    void configEntriesParseAndBookKeysPassExchangeFormat() {
        assertEquals(new ShopBookKey.Spec("thunderstrike", 3, true),
                ShopBookKey.parse("custom:thunderstrike:3"));
        assertEquals(new ShopBookKey.Spec("mending", 1, false),
                ShopBookKey.parse("VANILLA:Mending:1"));
        assertNull(ShopBookKey.parse("custom:veinminer:0"));
        assertNull(ShopBookKey.parse("custom:veinminer:11"));
        assertNull(ShopBookKey.parse("custom:veinminer"));
        assertNull(ShopBookKey.parse("unknown:veinminer:3"));
        assertNull(ShopBookKey.parse(null));
        for (String key : new String[]{"BOOK_CUSTOM_A_10", "BOOK_VANILLA_BANE_OF_ARTHROPODS_5"}) {
            assertTrue(key.matches("[A-Z][A-Z0-9_:]{0,127}"), key);
            assertNotNull(ShopBookKey.fromKey(key), key);
        }
        assertFalse(ShopBookKey.isKey("DIRT"));
        assertFalse(ShopBookKey.isKey("BOOK_CUSTOM__3"));
        assertFalse(ShopBookKey.isKey("BOOK_CUSTOM_A_0"));
        assertFalse(ShopBookKey.isKey("BOOK_CUSTOM_A_11"));
    }

    @Test
    void exchangeAcceptsBookOnlyWithMatchingAndValidKey() {
        var good = exchange("BOOK_CUSTOM_VEINMINER_3", "BOOK_CUSTOM_VEINMINER_3");
        assertTrue(good.valid());
        assertTrue(good.isBook());
        assertFalse(exchange("BOOK_CUSTOM_VEINMINER_3", "BOOK_CUSTOM_LIFESTEAL_3").valid());
        assertFalse(exchange("DIRT", "DIRT").valid(), "книжный ключ обязателен для сделки с книгой");
        assertFalse(exchange("DIRT", null).isBook());
        assertTrue(exchange("DIRT", null).valid());
    }

    private static ShopExchange exchange(String material, String book) {
        return new ShopExchange(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                true, material, 1, 10, 10, 0, 0, Map.of("DIAMOND", 1),
                "before", "after", 0, 0, null, null, book);
    }
}
