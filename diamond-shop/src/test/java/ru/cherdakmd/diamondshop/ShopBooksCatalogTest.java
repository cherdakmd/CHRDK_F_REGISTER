package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Книги чар задаются секцией {@code books:}, а не {@code items:}: пользовательский
 * каталог не перезаписывается миграцией, а новые разделы появляются у всех.
 */
class ShopBooksCatalogTest {

    @TempDir
    Path dir;

    private static YamlConfiguration shipped() throws Exception {
        try (var in = ShopBooksCatalogTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static List<ShopConfig.ShopItem> books(ShopConfig.ShopConfigData data) {
        return data.items().stream().filter(ShopConfig.ShopItem::isBook).toList();
    }

    @Test
    void shippedCatalogKeepsLegacyItemsAndAddsTwoBookSections() throws Exception {
        var yaml = shipped();
        assertEquals(249, yaml.getMapList("items").size(), "обычный каталог не изменился");
        var data = ShopConfig.parse(yaml, Logger.getAnonymousLogger());
        assertEquals(367, data.items().size());
        var books = books(data);
        assertEquals(118, books.size());
        assertEquals(76, books.stream().filter(b -> b.category().equals("books-custom")).count());
        assertEquals(42, books.stream().filter(b -> b.category().equals("books-vanilla")).count());
        assertEquals(books.size(), books.stream().map(ShopConfig.ShopItem::material).distinct().count());
    }

    @Test
    void everyBookHasMaxLevelOneOfferFixedPriceAndOwnTariff() throws Exception {
        var books = books(ShopConfig.parse(shipped(), Logger.getAnonymousLogger()));
        for (var book : books) {
            var spec = book.spec();
            assertNotNull(spec, book.material());
            assertEquals(book.material(), spec.key());
            assertEquals(book.material(), book.book());
            assertEquals(spec.category(), book.category());
            assertTrue(book.sellEnabled(), book.material());
            assertEquals(2.0, book.purchaseMultiplier(), book.material());
            assertEquals(1.0, book.minMultiplier(), book.material());
            assertEquals(1.0, book.maxMultiplier(), book.material());
            assertTrue(book.basePrice() >= 60 && book.basePrice() <= 400, book.material());
            assertTrue(ShopBookKey.isKey(book.material()), book.material());
            assertTrue(book.material().matches("[A-Z][A-Z0-9_:]{0,127}"), book.material());
            assertTrue(book.label().startsWith("книга "), book.label());
        }
    }

    @Test
    void buybackIsCheaperThanPurchaseAndQuoteAuditIsClean() throws Exception {
        var data = ShopConfig.parse(shipped(), Logger.getAnonymousLogger());
        var store = new ShopStore(dir, Logger.getAnonymousLogger());
        store.load();
        var service = new ShopService(data, store, () -> null);
        for (var book : books(data)) {
            long ask = service.askPrice(book), bid = service.bidPrice(book);
            assertTrue(ask > 0 && bid > 0, book.material() + ": ask=" + ask + ", bid=" + bid);
            assertTrue(bid < ask, book.material() + ": купил → продал дороже");
            assertEquals(1, service.batchSize(book), book.material() + ": книги продаются поштучно");
        }
        assertEquals(0, service.auditQuotes().errors(), "все котировки каталога корректны");
        assertTrue(ShopCatalog.tabs(data.items()).size() <= ShopLayout.MAX_TABS,
                "все вкладки влезают в расширенную витрину");
    }

    @Test
    void disabledBooksLeaveLegacyCatalogUntouched() throws Exception {
        var yaml = shipped();
        yaml.set("books.enabled", false);
        var data = ShopConfig.parse(yaml, Logger.getAnonymousLogger());
        assertEquals(249, data.items().size());
        assertTrue(books(data).isEmpty());
    }
}
