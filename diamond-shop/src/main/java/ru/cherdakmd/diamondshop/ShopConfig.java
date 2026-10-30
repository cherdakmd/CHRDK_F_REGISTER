package ru.cherdakmd.diamondshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Конфигурация алмазного магазина (config.yml аддона): курс валюты, параметры ценообразования
 * и каталог торгуемых товаров. Невалидные записи пропускаются с предупреждением в лог.
 */
public final class ShopConfig {

    /**
     * Один торгуемый товар.
     *
     * @param material       имя материала Bukkit в верхнем регистре
     * @param category       id раздела ({@link ShopCategory#id()}), у старых конфигов — {@code misc}
     * @param basePrice      базовая цена за единицу в алмазах
     * @param refStock       эталонный запас для динамической цены
     * @param minMultiplier  нижняя граница множителя цены
     * @param maxMultiplier  верхняя граница множителя цены
     */
    public record ShopItem(
            String material,
            String category,
            double basePrice,
            long refStock,
            double minMultiplier,
            double maxMultiplier,
            boolean sellEnabled
    ) {
        public ShopItem(String material, String category, double basePrice, long refStock,
                        double minMultiplier, double maxMultiplier) {
            this(material, category, basePrice, refStock, minMultiplier, maxMultiplier, true);
        }
    }

    /** Сырые значения курса материал → алмазы (имя материала без валидации Material — для тестов). */
    public record ShopConfigData(
            Map<String, Integer> currencyRates,
            double stockRecoveryPerHour,
            int maxWithdraw,
            double defaultMinMultiplier,
            double defaultMaxMultiplier,
            List<ShopItem> items,
            double spreadPercent,
            double dailyTurnoverLimit,
            int historyMaxPoints,
            long historyIntervalMillis
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
        double spread = Math.clamp(config.getDouble("pricing.spread-percent", 10.0), 0d, 90d);
        double dailyLimit = Math.max(0, config.getDouble("pricing.daily-turnover-limit", 0d));
        int historyPoints = Math.clamp(config.getInt("pricing.history-points", 24), 4, 168);
        long historyInterval = Math.clamp(config.getLong("pricing.history-interval-minutes", 60), 5, 1440) * 60_000L;

        List<ShopItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> raw : config.getMapList("items")) {
            ShopItem item = parseItem(raw, minMult, maxMult, logger);
            if (item == null) {
                continue;
            }
            if (!seen.add(item.material())) {
                logger.warning("[DiamondShop] items: дубликат товара '" + item.material()
                        + "' — вторая запись пропущена.");
                continue;
            }
            items.add(item);
        }
        return new ShopConfigData(rates, recovery, maxWithdraw, minMult, maxMult, List.copyOf(items),
                spread, dailyLimit, historyPoints, historyInterval);
    }

    private static ShopItem parseItem(Map<?, ?> raw, double defMin, double defMax, Logger logger) {
        Object materialObj = raw.get("material");
        String material = materialObj == null ? "" : String.valueOf(materialObj).trim().toUpperCase(java.util.Locale.ROOT);
        Material bukkitMaterial = material.isBlank() ? null : Material.matchMaterial(material);
        if (bukkitMaterial == null || !bukkitMaterial.isItem()
                || bukkitMaterial == Material.DIAMOND || bukkitMaterial == Material.DIAMOND_BLOCK) {
            logger.warning("[DiamondShop] items: неизвестный материал или валюта '" + material + "' — пропускаю.");
            return null;
        }
        if ((bukkitMaterial == Material.DIAMOND_ORE || bukkitMaterial == Material.DEEPSLATE_DIAMOND_ORE)
                && !Boolean.FALSE.equals(raw.get("sell-enabled"))) {
            logger.warning("[DiamondShop] Алмазные руды доступны только для покупки: " + material);
            return null;
        }
        if (raw.containsKey("sell-enabled") && !(raw.get("sell-enabled") instanceof Boolean)) {
            logger.warning("[DiamondShop] items." + material + ": sell-enabled должен быть true/false — пропускаю.");
            return null;
        }
        double basePrice = numberOf(raw.get("base-price"), 0);
        if (!Double.isFinite(basePrice) || basePrice <= 0) {
            logger.warning("[DiamondShop] items." + material + ": base-price должен быть > 0 — пропускаю.");
            return null;
        }
        long refStock = Math.max(1, (long) numberOf(raw.get("ref-stock"), 1000));
        double minMult = Math.clamp(numberOf(raw.get("min-multiplier"), defMin), 0.01, 1d);
        double maxMult = Math.clamp(numberOf(raw.get("max-multiplier"), defMax), 1d, 100d);
        return new ShopItem(material, categoryOf(raw.get("category"), logger, material),
                basePrice, refStock, minMult, maxMult, !Boolean.FALSE.equals(raw.get("sell-enabled")));
    }

    /**
     * Раздел товара из конфига. Пустое поле — тихий фолбэк в {@code misc}
     * (совместимость со старыми конфигами), неизвестное значение — с предупреждением.
     */
    private static String categoryOf(Object raw, Logger logger, String material) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return ShopCategory.DEFAULT.id();
        }
        Optional<ShopCategory> parsed = ShopCategory.byId(String.valueOf(raw));
        if (parsed.isEmpty() || parsed.get() == ShopCategory.ALL) {
            logger.warning("[DiamondShop] items." + material + ": неизвестный раздел '" + raw
                    + "' — товар попадёт в '" + ShopCategory.DEFAULT.id() + "'.");
            return ShopCategory.DEFAULT.id();
        }
        return parsed.get().id();
    }

    private static double numberOf(Object value, double fallback) {
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
        try {
            double parsed = value == null ? fallback : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
