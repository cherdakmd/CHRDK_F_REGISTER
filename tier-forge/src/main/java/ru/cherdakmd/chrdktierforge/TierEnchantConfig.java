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
 *
 * <p>1.4.128: секция {@code enchantments.custom.*} — кастомные чары
 * CHRDK_Enchants. Это мягкая зависимость: без аддона чар настройки просто
 * игнорируются. Проклятия допускаются только с {@code custom-curse-min-tier}
 * и только если аддон чар не выключил их у себя.</p>
 *
 * @param enabled                включены ли ванильные зачарования
 * @param chancePercent          базовый шанс ванильных чар для проклятого тира
 * @param maxPerItem             потолок ванильных чар
 * @param maxLevel               потолок уровня ванильных чар
 * @param customEnabled          включены ли кастомные чары аддона
 * @param customChancePercent    базовый шанс кастомной чары для проклятого тира
 * @param customMaxPerItem       потолок кастомных чар на предмет
 * @param customCurseChancePercent доля кастомных выдач, ставших проклятием
 * @param customCurseMinTier     с какого индекса тира (0..6) допустимы проклятия
 */
public record TierEnchantConfig(boolean enabled, int chancePercent, int maxPerItem, int maxLevel,
                                boolean customEnabled, int customChancePercent,
                                int customMaxPerItem, int customCurseChancePercent,
                                int customCurseMinTier) {

    /** По умолчанию: 20% на проклятом, до 2 чар, уровень не выше III. */
    public static final TierEnchantConfig DEFAULT =
            new TierEnchantConfig(true, 20, 2, 3, true, 18, 1, 15, 5);

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
        boolean customEnabled = config.getBoolean("enchantments.custom.enabled", true);
        int customChance = clamp(config.getInt("enchantments.custom.chance-percent", 18),
                0, 100, logger, "enchantments.custom.chance-percent");
        int customMaxPerItem = clamp(config.getInt("enchantments.custom.max-per-item", 1),
                0, 2, logger, "enchantments.custom.max-per-item");
        int customCurseChance = clamp(
                config.getInt("enchantments.custom.curse-chance-percent", 15),
                0, 100, logger, "enchantments.custom.curse-chance-percent");
        int customCurseMinTier = clamp(
                config.getInt("enchantments.custom.curse-min-tier", 5),
                0, TierDef.LADDER.size() - 1, logger,
                "enchantments.custom.curse-min-tier");
        return new TierEnchantConfig(enabled, chance, maxPerItem, maxLevel, customEnabled,
                customChance, customMaxPerItem, customCurseChance, customCurseMinTier);
    }

    /** Сколько ванильных чар можно выдать (0 — выключено). */
    public int itemLimit() {
        return enabled ? maxPerItem : 0;
    }

    /** Потолок уровня ванильного зачарования (0 — выключено). */
    public int levelLimit() {
        return enabled ? maxLevel : 0;
    }

    /** Сколько кастомных чар можно выдать (0 — выключено или аддон не настроен). */
    public int customItemLimit() {
        return customEnabled ? customMaxPerItem : 0;
    }

    /** Допустимы ли проклятия на этом тире. */
    public boolean cursesAllowedAt(int tierIndex) {
        return customCurseChancePercent > 0 && tierIndex >= customCurseMinTier;
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
