package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт config.yml: шалкер выведен, daily-cap включён, все лоты стоят ≥2 ⛁.
 *
 * <p>{@link ShopConfig#parse(FileConfiguration, Logger)} с настоящим каталогом в
 * юнит-тестах не вызывается: {@code Material#isItem()} в Paper 26.3 тянет
 * {@code Registry}, которого нет без сервера (см. {@link ShopBooksCatalogTest}).
 * Поэтому эталон читается как {@code YamlConfiguration}, а parse проверяется на
 * том же файле с очищенным {@code items:} — новые ключи разбираются без Registry.</p>
 */
final class ShopConfigCapTest {

    private static final Logger LOG = Logger.getLogger("shop-config-cap-test");

    private static YamlConfiguration shipped() throws Exception {
        try (var in = ShopConfigCapTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    /** Разбор новых ключей без вызова Material#isItem(). */
    private static ShopConfig.ShopConfigData parseKeysOnly() throws Exception {
        var yaml = shipped();
        yaml.set("items", new ArrayList<>());
        yaml.set("books.enabled", false);
        return ShopConfig.parse(yaml, LOG);
    }

    /** Эталонный каталог без Bukkit Registry: обычные товары + книги из books:. */
    private static List<ShopConfig.ShopItem> rawCatalog() throws Exception {
        var yaml = shipped();
        List<ShopConfig.ShopItem> items = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("items")) {
            Object min = raw.get("min-multiplier");
            Object max = raw.get("max-multiplier");
            String category = raw.get("category").toString();
            items.add(new ShopConfig.ShopItem(
                    raw.get("material").toString(), category,
                    ((Number) raw.get("base-price")).doubleValue(),
                    ((Number) raw.get("ref-stock")).longValue(),
                    min instanceof Number n ? n.doubleValue() : 0.25,
                    max instanceof Number n ? n.doubleValue() : 4.0,
                    !Boolean.FALSE.equals(raw.get("sell-enabled")),
                    ShopConfig.purchaseMultiplier(yaml, category)));
        }
        items.addAll(ShopConfig.parseBooks(yaml, LOG));
        return items;
    }

    @Test
    void shulkerShellIsRetiredAndAbsentFromTheCatalog() throws Exception {
        var yaml = shipped();
        assertFalse(yaml.getMapList("items").stream()
                .anyMatch(row -> "SHULKER_SHELL".equals(row.get("material"))));
        assertTrue(yaml.getStringList("catalog.retired-materials").contains("SHULKER_SHELL"));

        ShopConfig.ShopConfigData keys = parseKeysOnly();
        assertTrue(keys.retiredMaterials().contains("SHULKER_SHELL"));
        assertTrue(keys.item("SHULKER_SHELL").isEmpty());

        // Даже если строка осталась в пользовательском config.yml, фильтр её убирает.
        var synthetic = List.of(
                new ShopConfig.ShopItem("STONE", "blocks", 0.05, 8000, 0.25, 4.0, true, 4),
                new ShopConfig.ShopItem("SHULKER_SHELL", "mobs", 25, 300, 0.25, 4.0, true, 6));
        assertEquals(1, ShopOverrides.EMPTY.apply(synthetic,
                Set.copyOf(keys.retiredMaterials())).size());

        List<ShopConfig.ShopItem> all = rawCatalog();
        assertTrue(all.size() > 300, "каталог книг и предметов загружен целиком");
        assertFalse(all.stream().anyMatch(item -> item.material().equals("SHULKER_SHELL")));
    }

    @Test
    void dailyCapIsRetiredWithDocumentedDefaults() throws Exception {
        ShopConfig.ShopConfigData config = parseKeysOnly();
        ShopCapMath.Settings settings = config.dailyCap();
        assertFalse(settings.enabled());
        assertEquals(2048, settings.base());
        assertEquals(256, settings.perRankLevel());
        assertEquals(16, settings.perPrestige());
        assertEquals(16_384, settings.max());
        assertEquals("shop-limit-boost", settings.entitlementId());
        assertEquals(0d, config.dailyTurnoverLimit(), "0 — ручной потолок не задан, а не «без лимита»");
        assertEquals(2048, ShopCapMath.compute(settings.base(), settings, ShopCapMath.Context.NONE));
        assertEquals(6928, ShopCapMath.compute(settings.base(), settings, ShopCapMath.Context.full()));
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        assertTrue(service.contains("public double dailyLimit(Player player)"));
        assertTrue(service.contains("return 0;"));
    }

    @Test
    void everyLotCostsAtLeastTwoWholeDiamonds() throws Exception {
        var yaml = shipped();
        double spread = yaml.getDouble("pricing.spread-percent", 10.0);
        int checked = 0;
        for (ShopConfig.ShopItem item : rawCatalog()) {
            double lower = PriceEngine.round(item.basePrice() * item.minMultiplier());
            double lowerBid = PriceEngine.bid(lower, spread);
            double lowerAsk = PriceEngine.ask(lower, spread);
            int initial = RecipeEconomy.batch(RecipeEconomy.Cost.of(lowerBid));
            int batch = WholeLotMath.fit(initial, lowerBid, lowerAsk, item.sellEnabled());
            assertTrue(batch >= 1, item.material() + ": лот не подобран");
            long ask = WholeTrades.price(lowerAsk, batch, true);
            long bid = item.sellEnabled() ? WholeTrades.price(lowerBid, batch, false) : 2;
            assertTrue(ask >= WholeLotMath.MIN_LOT_PRICE,
                    item.material() + ": покупка лота " + ask + " ⛁");
            assertTrue(bid >= WholeLotMath.MIN_LOT_PRICE,
                    item.material() + ": скупка лота " + bid + " ⛁");
            checked++;
        }
        assertTrue(checked > 300);
    }

    @Test
    void overridesAreAppliedToTheReferenceCatalog() throws Exception {
        ShopOverrides overrides = ShopOverrides.EMPTY.copy();
        assertTrue(overrides.set("STONE", "base-price", "3.5"));
        overrides.remove("DIRT");
        List<ShopConfig.ShopItem> all = rawCatalog();
        List<ShopConfig.ShopItem> effective = overrides.apply(all,
                Set.copyOf(parseKeysOnly().retiredMaterials()));
        assertEquals(3.5d, effective.stream()
                .filter(item -> item.material().equals("STONE")).findFirst().orElseThrow().basePrice());
        assertTrue(effective.stream().noneMatch(item -> item.material().equals("DIRT")));
        assertEquals(Set.of("SHULKER_SHELL"), Set.copyOf(parseKeysOnly().retiredMaterials()));
    }
}
