package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.FileConfiguration;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * RC16: настройки {@code forge-shop.enabled/prices}. Неверная цена ЗАКРЫВАЕТ SKU,
 * а не подменяется дефолтом; обратной скупки расходников нет вообще.
 */
final class ForgeShopPrices {

    private final boolean enabled;
    private final Map<String, Long> prices;

    private ForgeShopPrices(boolean enabled, Map<String, Long> prices) {
        this.enabled = enabled;
        this.prices = Map.copyOf(prices);
    }

    static ForgeShopPrices parse(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("forge-shop.enabled", true);
        Map<String, Long> prices = new LinkedHashMap<>();
        for (var sku : ForgeMarketCatalog.SKUS) {
            if (!config.getBoolean("forge-shop.operations." + sku.id(), true)) {
                prices.put(sku.id(), -1L);
                continue;
            }
            String raw = config.getString("forge-shop.prices." + sku.id(), null);
            long value;
            if (raw == null || raw.isBlank()) {
                value = sku.price();
            } else {
                try {
                    value = Long.parseLong(raw.trim());
                } catch (NumberFormatException invalid) {
                    value = -1L;
                }
            }
            if (!ForgeMarketCatalog.validPrice(value)) {
                logger.warning("[DiamondShop] forge-shop.prices." + sku.id()
                        + " задана неверно — SKU закрыт, подмены ценой по умолчанию нет.");
                prices.put(sku.id(), -1L);
            } else {
                prices.put(sku.id(), value);
            }
        }
        return new ForgeShopPrices(enabled, prices);
    }

    boolean enabled() { return enabled; }

    /** -1 — товар закрыт (отключён, неверная цена или весь раздел выключен). */
    long price(String sku) {
        if (!enabled) return -1L;
        return prices.getOrDefault(sku, -1L);
    }
}
