package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml}: кривая, опыт за действия, бонусы видов, имена и
 * хранение. Неизменяемый снимок — пересоздаётся при {@code /petsadmin reload}.
 *
 * <p>Все числа клампятся. Кривой конфиг не должен превращать прокачку в ноль
 * или бесконечность, а опечатка в бонусе — ломать сервер: максимумы заданы
 * отдельными полями и перечислены в комментариях к {@code config.yml}.</p>
 */
public final class PetsConfig {

    /** Опыт за события. Все значения — за одно событие до видового множителя. */
    public record Xp(int perKill, int perAssist, int perFish, int perSleep,
                     double catMultiplier, int maxPerMinute) {
    }

    /** Настройки вида: включён ли, базовое здоровье и бонусы. */
    public record Species(boolean enabled, double baseHealth, PetBuffs.Settings buffs) {

        static Species disabled() {
            return new Species(false, 20.0, PetBuffs.Settings.NONE);
        }
    }

    private final boolean enabled;
    private final String locale;
    private final int refreshTicks;
    private final int saveIntervalSeconds;
    private final double interactRadius;
    private final double xpRadius;
    private final double ownerRadius;
    private final int topSize;
    private final PetCurve.Params curve;
    private final Xp xp;
    private final Map<PetSpecies, Species> species;
    private final int minNameLength;
    private final int maxNameLength;
    private final boolean showLevel;
    private final int keepDeadDays;

    private PetsConfig(boolean enabled, String locale, int refreshTicks, int saveIntervalSeconds,
                       double interactRadius, double xpRadius, double ownerRadius, int topSize,
                       PetCurve.Params curve, Xp xp, Map<PetSpecies, Species> species,
                       int minNameLength, int maxNameLength, boolean showLevel, int keepDeadDays) {
        this.enabled = enabled;
        this.locale = locale;
        this.refreshTicks = refreshTicks;
        this.saveIntervalSeconds = saveIntervalSeconds;
        this.interactRadius = interactRadius;
        this.xpRadius = xpRadius;
        this.ownerRadius = ownerRadius;
        this.topSize = topSize;
        this.curve = curve;
        this.xp = xp;
        this.species = species;
        this.minNameLength = minNameLength;
        this.maxNameLength = maxNameLength;
        this.showLevel = showLevel;
        this.keepDeadDays = keepDeadDays;
    }

    public static PetsConfig parse(FileConfiguration config, Logger logger) {
        ConfigurationSection general = section(config, "general");
        ConfigurationSection levels = section(config, "levels");
        ConfigurationSection xp = section(config, "xp");
        ConfigurationSection names = section(config, "names");
        ConfigurationSection storage = section(config, "storage");

        int maxLevel = (int) clamp(levels.getInt("max-level", 10), 2L, 100L);
        long baseXp = clamp(levels.getLong("base-xp", 40L), 1L, 1_000_000L);
        double growth = clamp(levels.getDouble("growth", 25.0), 0.0, 100_000.0);
        PetCurve.Params curve = new PetCurve.Params(maxLevel, baseXp, growth);

        int minName = (int) clamp(names.getInt("min-length", 2), 1L, 16L);
        int maxName = (int) clamp(names.getInt("max-length", 16), 1L, 32L);
        if (maxName < minName) {
            maxName = minName;
            if (logger != null) {
                logger.warning("pets: names.max-length меньше min-length — поднят до " + minName + ".");
            }
        }

        Xp xpSettings = new Xp(
                (int) clamp(xp.getInt("per-kill", 8), 0L, 10_000L),
                (int) clamp(xp.getInt("per-assist", 5), 0L, 10_000L),
                (int) clamp(xp.getInt("per-fish", 6), 0L, 10_000L),
                (int) clamp(xp.getInt("per-sleep", 25), 0L, 10_000L),
                clamp(xp.getDouble("cat-multiplier", 1.5), 0.0, 10.0),
                (int) clamp(xp.getInt("max-per-minute", 120), 0L, 100_000L));

        Map<PetSpecies, Species> species = new EnumMap<>(PetSpecies.class);
        for (PetSpecies value : PetSpecies.values()) {
            ConfigurationSection raw = config.getConfigurationSection("species." + value.id());
            if (raw == null) {
                raw = new MemoryConfiguration();
            }
            double baseHealth = clamp(raw.getDouble("base-health",
                    value == PetSpecies.WOLF ? 20.0 : 10.0), 1.0, 200.0);
            PetBuffs.Settings buffs = new PetBuffs.Settings(
                    raw.getDouble("health-per-level", value == PetSpecies.WOLF ? 2.0 : 1.0),
                    raw.getDouble("max-health-bonus", value == PetSpecies.WOLF ? 18.0 : 9.0),
                    raw.getDouble("damage-per-level", value == PetSpecies.WOLF ? 0.25 : 0.0),
                    raw.getDouble("max-damage-bonus", value == PetSpecies.WOLF ? 2.25 : 0.0),
                    raw.getDouble("speed-percent-per-level", value == PetSpecies.WOLF ? 0.4 : 0.35),
                    raw.getDouble("max-speed-percent", value == PetSpecies.WOLF ? 3.6 : 3.15),
                    raw.getDouble("owner-speed-percent-per-level", value == PetSpecies.WOLF ? 0.3 : 0.0),
                    raw.getDouble("max-owner-speed-percent", value == PetSpecies.WOLF ? 2.7 : 0.0),
                    raw.getDouble("owner-luck-per-level", value == PetSpecies.CAT ? 0.1 : 0.0),
                    raw.getDouble("max-owner-luck", value == PetSpecies.CAT ? 0.9 : 0.0));
            boolean enabled = raw.getBoolean("enabled", true);
            species.put(value, new Species(enabled, round(baseHealth, 2), buffs));
        }

        if (logger != null && !species.get(PetSpecies.WOLF).enabled()
                && !species.get(PetSpecies.CAT).enabled()) {
            logger.warning("pets: оба вида выключены — модуль не будет никого принимать.");
        }

        return new PetsConfig(
                general.getBoolean("enabled", true),
                general.getString("locale", "ru"),
                (int) clamp(general.getInt("refresh-ticks", 20), 5L, 200L),
                (int) clamp(general.getInt("save-interval-seconds", 60), 5L, 3600L),
                clamp(general.getDouble("interact-radius", 8.0), 2.0, 32.0),
                clamp(general.getDouble("xp-radius", 12.0), 2.0, 32.0),
                clamp(general.getDouble("owner-radius", 8.0), 2.0, 32.0),
                (int) clamp(general.getInt("top-size", 10), 3L, 50L),
                curve, xpSettings, species,
                minName, maxName,
                names.getBoolean("show-level", true),
                (int) clamp(storage.getInt("keep-dead-days", 30), 0L, 3650L));
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section == null ? new MemoryConfiguration() : section;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
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

    public double interactRadius() {
        return interactRadius;
    }

    public double xpRadius() {
        return xpRadius;
    }

    public double ownerRadius() {
        return ownerRadius;
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

    /** Настройки вида; неизвестный вид получает выключенный профиль. */
    public Species species(PetSpecies species) {
        if (species == null) {
            return Species.disabled();
        }
        return this.species.getOrDefault(species, Species.disabled());
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
}
