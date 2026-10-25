package ru.cherdakmd.chrdkclasses;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} классов (1.4.164).
 *
 * <p>Конфиг управляет темпом, а не балансом: деревья и числа эффектов живут в
 * {@link ClassCatalog}. Числа зажимаются при чтении — отрицательный шаг уровней
 * или кулдаун в миллион часов не должен превращать механику в кашу.</p>
 */
public final class ClassConfig {

    /** Потолок шага «сколько уровней навыков на уровень класса». */
    public static final int MAX_LEVELS_PER_LEVEL = 100;
    /** Потолок очков за уровень. */
    public static final int MAX_POINTS_PER_LEVEL = 5;
    /** Потолок стартовых очков. */
    public static final int MAX_STARTING_POINTS = 10;
    /** Потолок кулдауна смены класса в днях. */
    public static final int MAX_CHANGE_DAYS = 365;
    /** Потолок кулдауна пересборки в часах. */
    public static final int MAX_RESPEC_HOURS = 720;
    /** Потолок интервала пульса в секундах. */
    public static final int MAX_PULSE_SECONDS = 60;
    /** Потолок «вне боя» в секундах. */
    public static final int MAX_OUT_OF_COMBAT_SECONDS = 60;

    private final boolean enabled;
    private final String locale;
    private final boolean actionbar;
    private final boolean effectsEnabled;
    private final int levelsPerLevel;
    private final int pointsPerLevel;
    private final int startingPoints;
    private final int changeCooldownDays;
    private final int respecCooldownHours;
    private final int pulseSeconds;
    private final int outOfCombatSeconds;

    private ClassConfig(boolean enabled, String locale, boolean actionbar, boolean effectsEnabled,
                        int levelsPerLevel, int pointsPerLevel, int startingPoints,
                        int changeCooldownDays, int respecCooldownHours, int pulseSeconds,
                        int outOfCombatSeconds) {
        this.enabled = enabled;
        this.locale = locale;
        this.actionbar = actionbar;
        this.effectsEnabled = effectsEnabled;
        this.levelsPerLevel = levelsPerLevel;
        this.pointsPerLevel = pointsPerLevel;
        this.startingPoints = startingPoints;
        this.changeCooldownDays = changeCooldownDays;
        this.respecCooldownHours = respecCooldownHours;
        this.pulseSeconds = pulseSeconds;
        this.outOfCombatSeconds = outOfCombatSeconds;
    }

    /** Умолчания — те же, что в ресурсном config.yml. */
    public static ClassConfig defaults() {
        return new ClassConfig(true, "ru", true, true, 30, 1, 1, 7, 24, 5, 8);
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static ClassConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;
        ClassConfig fallback = defaults();
        String locale = source.getString("general.locale", fallback.locale);
        locale = locale == null || locale.isBlank()
                ? fallback.locale : locale.trim().toLowerCase(Locale.ROOT);
        return new ClassConfig(
                source.getBoolean("general.enabled", fallback.enabled),
                locale,
                source.getBoolean("general.actionbar", fallback.actionbar),
                source.getBoolean("effects.enabled", fallback.effectsEnabled),
                (int) clamp(source, "general.levels-per-level", fallback.levelsPerLevel,
                        5, MAX_LEVELS_PER_LEVEL, logger),
                (int) clamp(source, "general.points-per-level", fallback.pointsPerLevel,
                        0, MAX_POINTS_PER_LEVEL, logger),
                (int) clamp(source, "general.starting-points", fallback.startingPoints,
                        0, MAX_STARTING_POINTS, logger),
                (int) clamp(source, "general.change-cooldown-days", fallback.changeCooldownDays,
                        0, MAX_CHANGE_DAYS, logger),
                (int) clamp(source, "general.respec-cooldown-hours", fallback.respecCooldownHours,
                        0, MAX_RESPEC_HOURS, logger),
                (int) clamp(source, "general.pulse-seconds", fallback.pulseSeconds,
                        1, MAX_PULSE_SECONDS, logger),
                (int) clamp(source, "general.regen-out-of-combat-seconds",
                        fallback.outOfCombatSeconds, 0, MAX_OUT_OF_COMBAT_SECONDS, logger));
    }

    private static long clamp(FileConfiguration source, String path, long fallback, long min,
                              long max, Logger logger) {
        long raw = source.getLong(path, fallback);
        long value = Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Classes] " + path + ": " + raw + " вне границ "
                    + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean effectsEnabled() {
        return effectsEnabled;
    }

    public int levelsPerLevel() {
        return levelsPerLevel;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public int changeCooldownDays() {
        return changeCooldownDays;
    }

    public long changeCooldownSeconds() {
        return changeCooldownDays * 86_400L;
    }

    public int respecCooldownHours() {
        return respecCooldownHours;
    }

    public long respecCooldownSeconds() {
        return respecCooldownHours * 3_600L;
    }

    public int pulseSeconds() {
        return pulseSeconds;
    }

    public long pulseTicks() {
        return Math.max(1, pulseSeconds) * 20L;
    }

    public int outOfCombatSeconds() {
        return outOfCombatSeconds;
    }

    public long outOfCombatMillis() {
        return outOfCombatSeconds * 1_000L;
    }
}
