package ru.cherdakmd.diamondshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Конфигурация алмазного магазина (config.yml аддона): курс валюты, параметры ценообразования
 * и каталог торгуемых товаров. Невалидные записи пропускаются с предупреждением в лог.
 */
public final class ShopConfig {

    /** Один торгуемый товар. */
    public record ShopItem(
            String material,
            double basePrice,
            long refStock,
            double minMultiplier,
            double maxMultiplier
    ) {
    }

    /** Сырые значения курса материал → алмазы (имя материала без валидации Material — для тестов). */
    public record ShopConfigData(
            Map<String, Integer> currencyRates,
            double stockRecoveryPerHour,
            int maxWithdraw,
            double defaultMinMultiplier,
            double defaultMaxMultiplier,
            List<ShopItem> items
    ) {
        /** Товар по имени материала (верхний регистр, как в конфиге). */
        public Optional<ShopItem> item(String material) {
            String key = material.toUpperCase(java.util.Locale.ROOT);
            return items.stream().filter(item -> item.material.equals(key)).findFirst();
        }
    }

    private ShopConfig() {
    }

    /** Парсинг конфигурации баккита; материалы валюты валидируются ТОЛЬКО при вызове из плагина. */
    public static ShopConfigData parse(FileConfiguration config, Logger logger) {
        Map<String, Integer> rates = new LinkedHashMap<>();
        ConfigurationSection currency = config.getConfigurationSection("currency");
        if (currency != null) {
            for (String key : currency.getKeys(false)) {
                int rate = currency.getInt(key, 0);
                if (rate <= 0) {
                    logger.warning("[DiamondShop] currency." + key + ": курс должен быть > 0 — пропускаю.");
                    continue;
                }
                rates.put(key.toUpperCase(java.util.Locale.ROOT), rate);
            }
        }
        if (rates.isEmpty()) {
            logger.warning("[DiamondShop] currency пуст — используется курс по умолчанию.");
            rates.put("DIAMOND", 1);
            rates.put("DIAMOND_ORE", 1);
            rates.put("DEEPSLATE_DIAMOND_ORE", 1);
            rates.put("DIAMOND_BLOCK", 9);
        }

        double recovery = Math.clamp(config.getDouble("pricing.stock-recovery-per-hour", 0.05), 0d, 1d);
        int maxWithdraw = Math.clamp(config.getInt("pricing.max-withdraw", 6400), 1, 640_000);
        double minMult = Math.clamp(config.getDouble("defaults.min-multiplier", 0.25), 0.01, 1d);
        double maxMult = Math.clamp(config.getDouble("defaults.max-multiplier", 4.0), 1d, 100d);

        List<ShopItem> items = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("items")) {
            ShopItem item = parseItem(raw, minMult, maxMult, logger);
            if (item != null) items.add(item);
        }
        return new ShopConfigData(rates, recovery, maxWithdraw, minMult, maxMult, List.copyOf(items));
    }

    private static ShopItem parseItem(Map<?, ?> raw, double defMin, double defMax, Logger logger) {
        Object materialObj = raw.get("material");
        String material = materialObj == null ? "" : String.valueOf(materialObj).trim().toUpperCase(java.util.Locale.ROOT);
        Material bukkitMaterial = material.isBlank() ? null : Material.matchMaterial(material);
        if (bukkitMaterial == null || !bukkitMaterial.isItem()
                || bukkitMaterial == Material.DIAMOND || bukkitMaterial == Material.DIAMOND_ORE
                || bukkitMaterial == Material.DEEPSLATE_DIAMOND_ORE || bukkitMaterial == Material.DIAMOND_BLOCK) {
            logger.warning("[DiamondShop] items: неизвестный материал или валюта '" + material + "' — пропускаю.");
            return null;
        }
        double basePrice = numberOf(raw.get("base-price"), 0);
        if (basePrice <= 0) {
            logger.warning("[DiamondShop] items." + material + ": base-price должен быть > 0 — пропускаю.");
            return null;
        }
        long refStock = Math.max(1, (long) numberOf(raw.get("ref-stock"), 1000));
        double minMult = Math.clamp(numberOf(raw.get("min-multiplier"), defMin), 0.01, 1d);
        double maxMult = Math.clamp(numberOf(raw.get("max-multiplier"), defMax), 1d, 100d);
        return new ShopItem(material, basePrice, refStock, minMult, maxMult);
    }

    private static double numberOf(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
