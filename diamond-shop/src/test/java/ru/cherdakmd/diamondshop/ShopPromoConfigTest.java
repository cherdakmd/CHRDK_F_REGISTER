package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Парсер promotions:: аддитивный, пул сверяется с живым каталогом. */
class ShopPromoConfigTest {

    private ShopConfig.ShopConfigData catalog() {
        List<ShopConfig.ShopItem> items = List.of(
                new ShopConfig.ShopItem("DIRT", "blocks", 0.02, 20000, 0.5, 2.0, true),
                new ShopConfig.ShopItem("STONE", "blocks", 0.05, 20000, 0.5, 2.0, true),
                new ShopConfig.ShopItem("SAND", "blocks", 0.05, 20000, 0.5, 2.0, false));
        return new ShopConfig.ShopConfigData(Map.of(), 0.0, 0, 1.0, 2.0, items,
                10.0, 0.0, ShopCapMath.Settings.DEFAULT, List.of(), 24, 3_600_000L);
    }

    private YamlConfiguration yaml(String body) {
        var cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(body);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return cfg;
    }

    @Test
    void missingSectionUsesDefaultsWithoutRewritingOwnerConfig() {
        var cfg = yaml("pricing:\n  spread-percent: 10.0\n");
        var settings = ShopPromoConfig.from(cfg, catalog(), Logger.getAnonymousLogger());
        assertTrue(settings.enabled());
        assertEquals(List.of("DIRT", "STONE"), settings.pool(),
                "в пуле остаются только реальные товары, доступные для сдачи");
        assertEquals(2, settings.featuredCount());
        assertEquals(50, settings.hotBuybackPercent());
        assertEquals(5000, settings.maxBonusPerPlayerPerDay());
        assertFalse(cfg.contains("promotions"), "парсер не дописывает секцию в конфиг владельца");
    }

    @Test
    void explicitValuesAreClampedAndUnknownMaterialsDropped() {
        var cfg = yaml("""
                promotions:
                  rotation-schema: 2
                  enabled: true
                  pool: [DIRT, STONE, SAND, NOT_A_REAL_ITEM]
                  featured-per-window: 99
                  hot-buyback-percent: 900
                  max-bonus-per-trade: -1
                  max-bonus-per-player-per-day: 999999999
                """);
        var settings = ShopPromoConfig.from(cfg, catalog(), Logger.getAnonymousLogger());
        assertTrue(settings.active());
        assertEquals(List.of("DIRT", "STONE"), settings.pool(),
                "SAND не принимается, неизвестного материала нет");
        assertEquals(2, settings.featuredCount());
        assertEquals(100, settings.hotBuybackPercent());
        assertEquals(0, settings.maxBonusPerTrade());
        assertEquals(100000, settings.maxBonusPerPlayerPerDay());
    }

    @Test
    void legacyProductionValuesCannotRestoreThreeResourcesOrLowCaps() {
        var cfg = yaml("""
                promotions:
                  enabled: true
                  featured-per-day: 3
                  hot-buyback-percent: 25
                  contract-reward: 12
                  series-reward: 16
                  max-bonus-per-trade: 32
                  max-bonus-per-player-per-day: 128
                """);
        var settings = ShopPromoConfig.from(cfg, catalog(), Logger.getAnonymousLogger());
        assertEquals(2, settings.featuredCount(), "пул теста содержит только два ресурса");
        assertEquals(50, settings.hotBuybackPercent());
        assertEquals(5000, settings.maxBonusPerTrade());
        assertEquals(5000, settings.maxBonusPerPlayerPerDay());
    }

    @Test
    void disabledOrEmptyPoolTurnsPromotionsOff() {
        var off = ShopPromoConfig.from(yaml("promotions:\n  enabled: false\n"),
                catalog(), Logger.getAnonymousLogger());
        assertFalse(off.active());
        var empty = ShopPromoConfig.from(yaml("promotions:\n  enabled: true\n  pool: [NOPE]\n"),
                catalog(), Logger.getAnonymousLogger());
        assertFalse(empty.active());
        assertFalse(ShopPromoConfig.Settings.disabled().active());
    }
}
