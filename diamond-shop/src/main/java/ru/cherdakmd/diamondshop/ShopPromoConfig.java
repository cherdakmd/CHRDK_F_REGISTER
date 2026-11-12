package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Настройки дневных акций из {@code promotions:} конфига аддона.
 *
 * <p>Парсер аддитивный: при отсутствии секции у старого сервера работают
 * значения по умолчанию, но файл владельца НЕ перезаписывается. Товары пула
 * сверяются с живым каталогом: несуществующие и недоступные для продажи
 * материалы отбрасываются, пустой пул честно выключает акции.</p>
 */
final class ShopPromoConfig {

    /** Материалы из эталонного каталога: разные разделы, все доступны для сдачи. */
    static final List<String> DEFAULT_POOL = List.of(
            "DIRT", "COBBLESTONE", "STONE", "GRAVEL", "SAND",
            "OAK_LOG", "SPRUCE_LOG", "WHEAT", "COAL", "REDSTONE",
            "IRON_INGOT", "RAW_IRON", "GOLD_INGOT", "DIAMOND_ORE");

    record Settings(boolean enabled, List<String> pool, int featuredCount, int hotBuybackPercent,
                    long contractUnits, long contractReward, int seriesKinds, long seriesReward,
                    long maxBonusPerTrade, long maxBonusPerPlayerPerDay) {

        static Settings disabled() {
            return new Settings(false, List.of(), 2, 0, 64, 0, 3, 0, 0, 0);
        }

        boolean active() {
            return enabled && !pool.isEmpty() && featuredCount > 0;
        }
    }

    static Settings defaults() {
        return new Settings(true, DEFAULT_POOL, 5, 50, 64, 0, 3, 0, 5000, 5000);
    }

    static Settings from(FileConfiguration config, ShopConfig.ShopConfigData catalog, Logger logger) {
        if (config == null || catalog == null) {
            return Settings.disabled();
        }
        ConfigurationSection section = config.getConfigurationSection("promotions");
        if (section == null) {
            logger.info("[Акции] Секция promotions: отсутствует — использованы стоковые значения, config.yml не перезаписан.");
            return fit(defaults(), catalog, logger);
        }
        Settings base = defaults();
        // Старый production-блок без rotation-schema не может сохранить режим 3/день/128.
        boolean modern = section.getInt("rotation-schema", 1) >= 2;
        Settings raw = new Settings(
                section.getBoolean("enabled", base.enabled()),
                strings(section, "pool", base.pool()),
                clampInt(modern ? section.getInt("featured-per-window", base.featuredCount())
                        : base.featuredCount(), 1, 10),
                clampInt(modern ? section.getInt("hot-buyback-percent", base.hotBuybackPercent())
                        : base.hotBuybackPercent(), 0, 100),
                64, 0, 3, 0,
                clampLong(modern ? section.getLong("max-bonus-per-trade", base.maxBonusPerTrade())
                        : base.maxBonusPerTrade(), 0, 10000),
                clampLong(modern ? section.getLong("max-bonus-per-player-per-day", base.maxBonusPerPlayerPerDay())
                        : base.maxBonusPerPlayerPerDay(), 0, 100000));
        return fit(raw, catalog, logger);
    }

    /** Оставляем в пуле только реальные товары, которые можно сдать в магазин. */
    private static Settings fit(Settings raw, ShopConfig.ShopConfigData catalog, Logger logger) {
        if (!raw.enabled()) {
            return raw;
        }
        Set<String> sellable = new LinkedHashSet<>();
        for (ShopConfig.ShopItem item : catalog.items()) {
            if (item.sellEnabled()) {
                sellable.add(item.material());
            }
        }
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        for (String material : raw.pool()) {
            String key = material == null ? "" : material.trim().toUpperCase(java.util.Locale.ROOT);
            if (sellable.contains(key)) {
                pool.add(key);
            } else if (!key.isEmpty()) {
                logger.warning("[Акции] Товар «" + key + "» отсутствует в каталоге или не принимается — исключён из пула.");
            }
        }
        int count = Math.min(raw.featuredCount(), pool.size());
        if (pool.isEmpty() || count == 0) {
            logger.warning("[Акции] Пул акций пуст — дневные акции выключены.");
            return new Settings(false, List.of(), raw.featuredCount(), raw.hotBuybackPercent(),
                    raw.contractUnits(), raw.contractReward(), raw.seriesKinds(), raw.seriesReward(),
                    raw.maxBonusPerTrade(), raw.maxBonusPerPlayerPerDay());
        }
        return new Settings(true, List.copyOf(pool), count, raw.hotBuybackPercent(),
                raw.contractUnits(), raw.contractReward(), Math.min(raw.seriesKinds(), pool.size()),
                raw.seriesReward(), raw.maxBonusPerTrade(), raw.maxBonusPerPlayerPerDay());
    }

    private static List<String> strings(ConfigurationSection section, String path, List<String> fallback) {
        List<?> values = section.getList(path, null);
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                out.add(text.trim());
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }

    private ShopPromoConfig() { }
}
