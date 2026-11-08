package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** SKU «+8 чанков навсегда»: условия, выдача и аддитивная миграция каталога. */
class ClaimsChunksProductTest {

    private YamlConfiguration yaml(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    @Test
    void permanentBonusSkuTermsAndConsoleGrant() throws Exception {
        var cfg = yaml("/config.yml");
        Map<?, ?> sku = cfg.getMapList("shop.products").stream()
                .filter(p -> "claims-chunks-plus".equals(p.get("id")))
                .findFirst().orElseThrow();
        assertEquals(159, sku.get("price"));
        assertEquals(30, sku.get("days"), "технический срок записи, не срок бонуса");
        assertEquals(List.of("claimadmin chunks %player% add 8"), sku.get("commands"));
        assertEquals(Boolean.FALSE, sku.get("trademc-export"), "26 карточек TradeMC не меняются");
        assertEquals(List.of(), sku.get("permissions"));
        assertEquals("", sku.get("group"));
        assertEquals("", sku.get("tag"));
        assertFalse(sku.containsKey("rank"));
        assertTrue(String.valueOf(sku.get("description")).contains("НАВСЕГДА"));
        assertTrue(String.valueOf(sku.get("description")).contains("не требует продления"));

        ShopCatalog catalog = ShopCatalog.from(cfg);
        assertTrue(catalog.problems().isEmpty(), catalog.problems().toString());
        assertEquals(33, catalog.products().size());
        var product = catalog.products().stream()
                .filter(p -> p.id().equals("claims-chunks-plus")).findFirst().orElseThrow();
        assertEquals(159, product.priceRubles());
        assertEquals(List.of("claimadmin chunks %player% add 8"), product.commands());
    }

    @Test
    void additiveMigrationKeepsOwnerPricesAndIsIdempotent() throws Exception {
        var fresh = yaml("/config.yml");
        var live = new YamlConfiguration();
        var own = Map.of("id", "owner-sku", "price", 777, "days", 7);
        live.set("shop.products", new ArrayList<Map<?, ?>>(List.of(own)));

        assertTrue(ClaimsChunksProductMigration.update(live, fresh));
        var products = live.getMapList("shop.products");
        assertEquals(2, products.size());
        assertEquals(777, products.get(0).get("price"), "владельческая цена не перезаписана");
        assertEquals(7, products.get(0).get("days"));
        assertEquals("claims-chunks-plus", products.get(1).get("id"));
        assertFalse(ClaimsChunksProductMigration.update(live, fresh), "повтор ничего не добавляет");
        assertEquals(2, live.getMapList("shop.products").size());
    }

    @Test
    void stockCatalogIsFullyReplacedOnlyByKnownAdditions() throws Exception {
        assertTrue(PresentationMigration.LATE_STOCK_PRODUCTS.contains("claims-chunks-plus"),
                "миграция презентации должна узнавать новый стоковый SKU");
        var live = yaml("/config.yml");
        assertFalse(ClaimsChunksProductMigration.update(live, yaml("/config.yml")),
                "в эталонном каталоге SKU уже есть");
    }
}
