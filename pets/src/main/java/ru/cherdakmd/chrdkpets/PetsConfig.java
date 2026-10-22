package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Разбор {@code config.yml}.
 *
 * <p>Класс чистый: на вход {@link FileConfiguration}, на выход неизменяемый
 * снимок. Значения клампятся здесь, а не в момент применения, поэтому
 * администратор видит в логе предупреждение о слишком большой цифре сразу
 * после {@code /petsadmin reload}, а не тогда, когда питомец внезапно получит
 * сотню сердец.</p>
 */
public final class PetsConfig {

    /** Опыт за события. {@code catMultiplier} касается только мирных событий. */
    public record Xp(int kill, int assist, int fish, int sleep,
                     double catMultiplier, int maxPerMinute) {

        public Xp {
            kill = clamp(kill, 0, 100);
            assist = clamp(assist, 0, 100);
            fish = clamp(fish, 0, 100);
            sleep = clamp(sleep, 0, 500);
            catMultiplier = clamp(catMultiplier, 1.0, 3.0);
            maxPerMinute = clamp(maxPerMinute, 0, 10_000);
        }
    }

    /** Настройки вида: включатель и бонусы. */
    public record Species(boolean enabled, PetBuffs.Settings buffs) {

        public static final Species OFF = new Species(false, PetBuffs.Settings.NONE);
    }

    private final boolean enabled;
    private final String locale;
    private final int refreshTicks;
    private final int saveIntervalSeconds;
    private final double ownerRadius;
    private final double xpRadius;
    private final int topSize;
    private final PetCurve.Params curve;
    private final Xp xp;
    private final Species wolf;
    private final Species cat;
    private final int minNameLength;
    private final int maxNameLength;
    private final boolean showLevel;
    private final int keepDeadDays;

    private PetsConfig(boolean enabled, String locale, int refreshTicks, int saveIntervalSeconds,
                       double ownerRadius, double xpRadius, int topSize, PetCurve.Params curve,
                       Xp xp, Species wolf, Species cat, int minNameLength, int maxNameLength,
                       boolean showLevel, int keepDeadDays) {
        this.enabled = enabled;
        this.locale = locale;
        this.refreshTicks = refreshTicks;
        this.saveIntervalSeconds = saveIntervalSeconds;
        this.ownerRadius = ownerRadius;
        this.xpRadius = xpRadius;
        this.topSize = topSize;
        this.curve = curve;
        this.xp = xp;
        this.wolf = wolf;
        this.cat = cat;
        this.minNameLength = minNameLength;
        this.maxNameLength = maxNameLength;
        this.showLevel = showLevel;
        this.keepDeadDays = keepDeadDays;
    }

    private static final PetsConfig DEFAULTS = new PetsConfig(true, "ru", 20, 60, 8.0, 12.0, 10,
                PetCurve.Params.DEFAULT,
                new Xp(8, 5, 6, 25, 1.5, 120),
                species(true, 2.0, 18.0, 0.25, 2.25, 0.4, 3.6, 0.3, 2.7, 0.0, 0.0),
                species(true, 1.0, 9.0, 0.0, 0.0, 0.35, 3.15, 0.0, 0.0, 0.1, 0.9),
                2, 16, true, 30);

    /** Значения по умолчанию — те же, что лежат в поставляемом config.yml. */
    public static PetsConfig defaults() {
        return DEFAULTS;
    }

    public static PetsConfig parse(FileConfiguration source, Logger logger) {
        if (source == null) {
            return defaults();
        }
        FileConfiguration config = source;
        boolean enabled = config.getBoolean("general.enabled", true);
        String locale = config.getString("general.locale", "ru");
        int refreshTicks = warn(logger, "general.refresh-ticks",
                clamp(config.getInt("general.refresh-ticks", 20), 10, 600), 10, 600);
        int saveInterval = warn(logger, "general.save-interval-seconds",
                clamp(config.getInt("general.save-interval-seconds", 60), 15, 3600), 15, 3600);
        double ownerRadius = clamp(config.getDouble("general.owner-radius", 8.0), 2.0, 32.0);
        double xpRadius = clamp(config.getDouble("general.xp-radius", 12.0), 4.0, 64.0);
        int topSize = clamp(config.getInt("general.top-size", 10), 3, 50);

        int maxLevel = clamp(config.getInt("levels.max-level", 10), 2, 100);
        long baseXp = clamp(config.getLong("levels.xp-base", 40L), 1L, 100_000L);
        double growth = clamp(config.getDouble("levels.xp-growth", 25.0), 0.0, 10_000.0);
        PetCurve.Params curve = new PetCurve.Params(maxLevel, baseXp, growth);

        Xp xp = new Xp(
                config.getInt("xp.kill", 8),
                config.getInt("xp.assist", 5),
                config.getInt("xp.fish", 6),
                config.getInt("xp.sleep", 25),
                config.getDouble("xp.cat-multiplier", 1.5),
                config.getInt("xp.max-per-minute", 120));

        Species wolf = species(config, logger, "wolf", DEFAULTS.wolf, maxLevel);
        Species cat = species(config, logger, "cat", DEFAULTS.cat, maxLevel);

        int minName = clamp(config.getInt("names.min-length", 2), 1, 32);
        int maxName = clamp(config.getInt("names.max-length", 16), minName, 32);
        boolean showLevel = config.getBoolean("names.show-level", true);
        int keepDeadDays = clamp(config.getInt("storage.keep-dead-days", 30), 0, 3650);

        if (locale == null || locale.isBlank()) {
            locale = "ru";
        }
        return new PetsConfig(enabled, locale.trim().toLowerCase(java.util.Locale.ROOT),
                refreshTicks, saveInterval, ownerRadius, xpRadius, topSize, curve, xp,
                wolf, cat, minName, maxName, showLevel, keepDeadDays);
    }

    private static Species species(FileConfiguration config, Logger logger,
                                   String id, Species fallback, int maxLevel) {
        ConfigurationSection section = config.getConfigurationSection("species." + id);
        if (section == null) {
            return fallback;
        }
        boolean enabled = section.getBoolean("enabled", true);
        PetBuffs.Settings buffs = new PetBuffs.Settings(
                section.getDouble("health-per-level", fallback.buffs().healthPerLevel()),
                section.getDouble("max-health-bonus", fallback.buffs().maxHealthBonus()),
                section.getDouble("damage-per-level", fallback.buffs().damagePerLevel()),
                section.getDouble("max-damage-bonus", fallback.buffs().maxDamageBonus()),
                section.getDouble("speed-percent-per-level",
                        fallback.buffs().speedPercentPerLevel()),
                section.getDouble("max-speed-percent", fallback.buffs().maxSpeedPercent()),
                section.getDouble("owner-speed-percent-per-level",
                        fallback.buffs().ownerSpeedPercentPerLevel()),
                section.getDouble("max-owner-speed-percent",
                        fallback.buffs().maxOwnerSpeedPercent()),
                section.getDouble("owner-luck-per-level", fallback.buffs().ownerLuckPerLevel()),
                section.getDouble("max-owner-luck", fallback.buffs().maxOwnerLuck()));
        int steps = Math.max(0, maxLevel - 1);
        double health = buffs.healthPerLevel() * steps;
        if (health > buffs.maxHealthBonus() + 1.0e-6) {
            logger.warning("[CHRDK_Pets] species." + id + ": health-per-level × "
                    + steps + " = " + health + " упирается в max-health-bonus "
                    + buffs.maxHealthBonus() + ".");
        }
        return new Species(enabled, buffs);
    }

    /** Настройки вида; {@code null} и неизвестное имя — выключенный вид. */
    public Species species(PetSpecies value) {
        if (value == null) {
            return Species.OFF;
        }
        return value == PetSpecies.CAT ? cat : wolf;
    }

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public int saveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public double ownerRadius() {
        return ownerRadius;
    }

    public double xpRadius() {
        return xpRadius;
    }

    public int topSize() {
        return topSize;
    }

    public PetCurve.Params curve() {
        return curve;
    }

    public Xp xp() {
        return xp;
    }

    public int minNameLength() {
        return minNameLength;
    }

    public int maxNameLength() {
        return maxNameLength;
    }

    public boolean showLevel() {
        return showLevel;
    }

    public int keepDeadDays() {
        return keepDeadDays;
    }

    /* --------------------------------------------------------------- служебное */

    private static Species species(boolean enabled, double healthPerLevel, double maxHealth,
                                   double damagePerLevel, double maxDamage,
                                   double speedPercentPerLevel, double maxSpeedPercent,
                                   double ownerSpeedPerLevel, double maxOwnerSpeedPercent,
                                   double ownerLuckPerLevel, double maxOwnerLuck) {
        return new Species(enabled, new PetBuffs.Settings(
                healthPerLevel, maxHealth, damagePerLevel, maxDamage,
                speedPercentPerLevel, maxSpeedPercent,
                ownerSpeedPerLevel, maxOwnerSpeedPercent,
                ownerLuckPerLevel, maxOwnerLuck));
    }

    private static int warn(Logger logger, String path, int value, int min, int max) {
        if (value < min) {
            logger.warning("[CHRDK_Pets] " + path + " меньше " + min
                    + " — берётся " + min + ".");
            return min;
        }
        if (value > max) {
            logger.warning("[CHRDK_Pets] " + path + " больше " + max
                    + " — берётся " + max + ".");
            return max;
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
