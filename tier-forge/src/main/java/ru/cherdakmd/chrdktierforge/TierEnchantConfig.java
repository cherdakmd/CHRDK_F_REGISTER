package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Настройки случайных зачарований тир-предметов ({@code enchantments.*}).
 *
 * <p>{@code chance-percent} — базовый шанс для проклятого тира; каждый тир выше
 * добавляет {@link TierEnchants#PER_TIER_BONUS}%. Уровень зачарования растёт по
 * лестнице до {@code max-level}. Зачарования никогда не снимаются: если предмет
 * уже зачарован игроком, плагин его не трогает.</p>
 */
public record TierEnchantConfig(boolean enabled, int chancePercent, int maxPerItem, int maxLevel) {

    /** По умолчанию: 20% на проклятом, до 2 чар, уровень не выше III. */
    public static final TierEnchantConfig DEFAULT = new TierEnchantConfig(true, 20, 2, 3);

    /** Разбор секции {@code enchantments.*}; кривые значения клампятся. */
    public static TierEnchantConfig parse(FileConfiguration config, Logger logger) {
        if (config == null) {
            return DEFAULT;
        }
        boolean enabled = config.getBoolean("enchantments.enabled", true);
        int chance = clamp(config.getInt("enchantments.chance-percent", 20), 0, 100, logger,
                "enchantments.chance-percent");
        int maxPerItem = clamp(config.getInt("enchantments.max-per-item", 2), 0, 3, logger,
                "enchantments.max-per-item");
        int maxLevel = clamp(config.getInt("enchantments.max-level", 3), 1, 5, logger,
                "enchantments.max-level");
        return new TierEnchantConfig(enabled, chance, maxPerItem, maxLevel);
    }

    /** Сколько чар можно выдать (0 — выключено). */
    public int itemLimit() {
        return enabled ? maxPerItem : 0;
    }

    /** Потолок уровня (0 — выключено). */
    public int levelLimit() {
        return enabled ? maxLevel : 0;
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            logger.warning("config: " + key + " = " + value + " вне " + min + ".."
                    + max + " — использую " + clamped);
        }
        return clamped;
    }
}
