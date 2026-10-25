package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Настройки универсальных аффиксов ({@code affixes.*} в config.yml).
 *
 * <p>{@code max-good}/{@code max-bad} — это потолок, а не обещание: тир сам
 * решает, сколько аффиксов положено предмету (проклятому — два дефекта,
 * легендарному — два бонуса). Ноль отключает знак целиком, не трогая второй.
 * </p>
 */
public record TierAffixConfig(boolean enabled, int maxGood, int maxBad) {

    /** Значение по умолчанию: включено, до двух аффиксов каждого знака. */
    public static final TierAffixConfig DEFAULT = new TierAffixConfig(true, 2, 2);

    /** Разбор секции {@code affixes.*}; кривые значения клампятся с предупреждением. */
    public static TierAffixConfig parse(FileConfiguration config, Logger logger) {
        if (config == null) {
            return DEFAULT;
        }
        boolean enabled = config.getBoolean("affixes.enabled", true);
        int maxGood = clamp(config.getInt("affixes.max-good", 2), logger, "affixes.max-good");
        int maxBad = clamp(config.getInt("affixes.max-bad", 2), logger, "affixes.max-bad");
        return new TierAffixConfig(enabled, maxGood, maxBad);
    }

    /** Сколько хороших аффиксов максимум. */
    public int goodLimit() {
        return enabled ? maxGood : 0;
    }

    /** Сколько плохих аффиксов максимум. */
    public int badLimit() {
        return enabled ? maxBad : 0;
    }

    private static int clamp(int value, Logger logger, String key) {
        int clamped = Math.max(0, Math.min(TierAffixes.MAX_PER_SIGN, value));
        if (clamped != value) {
            logger.warning("config: " + key + " = " + value + " вне 0.."
                    + TierAffixes.MAX_PER_SIGN + " — использую " + clamped);
        }
        return clamped;
    }
}
