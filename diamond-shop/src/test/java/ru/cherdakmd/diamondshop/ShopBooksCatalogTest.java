package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Книги чар задаются секцией {@code books:}, а не {@code items:}: пользовательский
 * каталог не перезаписывается миграцией, а новые разделы появляются у всех.
 * {@link ShopConfig#parse} не вызывается: {@code Material#isItem()} в Paper 26.3
 * инициализирует {@code Registry}, недоступный в юнит-тестах без сервера.
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

    private static ShopConfig.ShopConfigData data(YamlConfiguration yaml) {
        List<ShopConfig.ShopItem> all = new ArrayList<>();
        for (var row : yaml.getMapList("items")) {
            String category = row.get("category").toString();
            var min = row.get("min-multiplier");
            var max = row.get("max-multiplier");
            all.add(new ShopConfig.ShopItem(row.get("material").toString(), category,
                    ((Number) row.get("base-price")).doubleValue(),
                    ((Number) row.get("ref-stock")).longValue(),
                    min instanceof Number n ? n.doubleValue() : .25,
                    max instanceof Number n ? n.doubleValue() : 4.0,
                    !Boolean.FALSE.equals(row.get("sell-enabled")),
                    ShopConfig.purchaseMultiplier(yaml, category)));
        }
        all.addAll(ShopConfig.parseBooks(yaml, Logger.getAnonymousLogger()));
        return new ShopConfig.ShopConfigData(Map.of("DIAMOND", 1, "DIAMOND_BLOCK", 9),
                0, 6400, .25, 4.0, List.copyOf(all), 10, 0, 24, 3600000);
    }

    private static List<ShopConfig.ShopItem> books(ShopConfig.ShopConfigData config) {
        return config.items().stream().filter(ShopConfig.ShopItem::isBook).toList();
    }

    @Test
    void shippedCatalogKeepsLegacyItemsAndAddsTwoBookSections() throws Exception {
        var yaml = shipped();
        assertEquals(248, yaml.getMapList("items").size(), "обычный каталог не изменился");
        var config = data(yaml);
        assertEquals(366, config.items().size());
        var books = books(config);
        assertEquals(118, books.size());
        assertEquals(76, books.stream().filter(b -> b.category().equals("books-custom")).count());
        assertEquals(42, books.stream().filter(b -> b.category().equals("books-vanilla")).count());
        assertEquals(books.size(), books.stream().map(ShopConfig.ShopItem::material).distinct().count());
    }

    @Test
    void everyBookHasMaxLevelOneOfferFixedPriceAndOwnTariff() throws Exception {
        var books = books(data(shipped()));
        for (var book : books) {
            var spec = book.spec();
            assertNotNull(spec, book.material());
            assertEquals(book.material(), spec.key());
            assertEquals(book.material(), book.book());
            assertEquals(spec.category(), book.category());
            assertTrue(book.sellEnabled(), book.material());
            assertEquals(1.0, book.purchaseMultiplier(), book.material());
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
        var config = data(shipped());
        var store = new ShopStore(dir, Logger.getAnonymousLogger());
        store.load();
        var service = new ShopService(config, store, () -> null);
        for (var book : books(config)) {
            long ask = service.askPrice(book), bid = service.bidPrice(book);
            assertTrue(ask > 0 && bid > 0, book.material() + ": ask=" + ask + ", bid=" + bid);
            assertTrue(bid < ask, book.material() + ": купил → продал дороже");
            assertEquals(1, service.batchSize(book), book.material() + ": книги продаются поштучно");
        }
        assertEquals(0, service.auditQuotes().errors(), "все котировки каталога корректны");
        assertTrue(ShopCatalog.tabs(config.items()).size() <= ShopLayout.MAX_TABS,
                "все вкладки влезают в расширенную витрину");
    }

    @Test
    void disabledBooksLeaveLegacyCatalogUntouched() throws Exception {
        var yaml = shipped();
        yaml.set("books.enabled", false);
        assertTrue(ShopConfig.parseBooks(yaml, Logger.getAnonymousLogger()).isEmpty());
        assertEquals(248, data(yaml).items().size());
    }
}
