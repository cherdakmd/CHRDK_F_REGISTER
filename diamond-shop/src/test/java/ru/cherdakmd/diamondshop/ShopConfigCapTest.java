package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Контракт config.yml: шалкер выведен, daily-cap включён, все лоты стоят ≥2 ⛁. */
final class ShopConfigCapTest {

    private static final Logger LOG = Logger.getLogger("shop-config-cap-test");

    private static ShopConfig.ShopConfigData stock() {
        return ShopConfig.parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile()), LOG);
    }

    @Test
    void shulkerShellIsRetiredAndAbsentFromTheCatalog() {
        ShopConfig.ShopConfigData config = stock();
        assertTrue(config.retiredMaterials().contains("SHULKER_SHELL"));
        assertTrue(config.item("SHULKER_SHELL").isEmpty());
        assertFalse(config.items().isEmpty());
        assertTrue(config.items().size() > 300, "каталог книг и предметов загружен целиком");
    }

    @Test
    void dailyCapIsEnabledWithDocumentedDefaults() {
        ShopConfig.ShopConfigData config = stock();
        ShopCapMath.Settings settings = config.dailyCap();
        assertTrue(settings.enabled());
        assertEquals(2048, settings.base());
        assertEquals(256, settings.perRankLevel());
        assertEquals(16, settings.perPrestige());
        assertEquals(16_384, settings.max());
        assertEquals("shop-limit-boost", settings.entitlementId());
        assertEquals(0d, config.dailyTurnoverLimit(), "0 — ручной потолок не задан, а не «без лимита»");
        assertEquals(2048, ShopCapMath.compute(settings.base(), settings, ShopCapMath.Context.NONE));
        assertEquals(6928, ShopCapMath.compute(settings.base(), settings, ShopCapMath.Context.full()));
    }

    @Test
    void everyLotCostsAtLeastTwoWholeDiamonds() {
        ShopConfig.ShopConfigData config = stock();
        double spread = config.spreadPercent();
        int checked = 0;
        for (ShopConfig.ShopItem item : config.items()) {
            double lower = PriceEngine.round(item.basePrice() * item.minMultiplier());
            double lowerBid = PriceEngine.bid(lower, spread);
            double lowerAsk = PriceEngine.ask(lower, spread);
            int initial = RecipeEconomy.batch(RecipeEconomy.Cost.of(lowerBid));
            int batch = WholeLotMath.fit(initial, lowerBid, lowerAsk, item.sellEnabled());
            assertTrue(batch >= 1, item.material() + ": лот не подобран");
            long ask = WholeTrades.price(lowerAsk, batch, true, item.purchaseMultiplier());
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
    void overridesAreAppliedAfterParsingTheReference() {
        ShopOverrides overrides = ShopOverrides.EMPTY.copy();
        assertTrue(overrides.set("STONE", "base-price", "3.5"));
        overrides.remove("DIRT");
        ShopConfig.ShopConfigData config = ShopConfig.parse(
                YamlConfiguration.loadConfiguration(
                        Path.of("src/main/resources/config.yml").toFile()), LOG, overrides);
        assertEquals(3.5d, config.item("STONE").orElseThrow().basePrice());
        assertTrue(config.item("DIRT").isEmpty());
        assertEquals(Set.of("SHULKER_SHELL"), Set.copyOf(config.retiredMaterials()));
    }
}
