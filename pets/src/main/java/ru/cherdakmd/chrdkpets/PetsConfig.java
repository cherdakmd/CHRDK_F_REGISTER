package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml}: кривая, опыт за действия, забота, трюки, бонусы
 * видов, имена и хранение. Неизменяемый снимок — пересоздаётся при
 * {@code /petsadmin reload}.
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

    /** Бонусы к опыту за близость, ночь и выходные. */
    public record Bonuses(double near, double nearRadius, double night, boolean nightWolfOnly,
                          double weekend, Set<DayOfWeek> weekendDays) {

        public PetXpRules.Factors factors() {
            return new PetXpRules.Factors(near, night, weekend);
        }

        /** Входит ли день недели в настроенные выходные. */
        public boolean weekend(DayOfWeek day) {
            return day != null && weekendDays.contains(day);
        }
    }

    /** Настройки трюков: шанс обучения и кулдаун исполнения. */
    public record Tricks(double learnChanceBase, double learnChancePerMood, int cooldownSeconds) {
    }

    /** Перемещение и переименование: кулдауны и запрет вызова в бою. */
    public record Movement(int callCooldownSeconds, int combatBlockSeconds,
                           int renameCooldownSeconds) {
    }

    /** Косметика: можно ли красить ошейник и включать свечение. */
    public record Cosmetics(boolean collarEnabled, boolean glowEnabled) {
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
    private final int maxPets;
    private final PetCurve.Params curve;
    private final Xp xp;
    private final Map<PetSpecies, Species> species;
    private final int minNameLength;
    private final int maxNameLength;
    private final boolean showLevel;
    private final int keepDeadDays;

    private final boolean careEnabled;
    private final PetCare.Settings care;
    private final Tricks tricks;
    private final Movement movement;
    private final Cosmetics cosmetics;
    private final Bonuses bonuses;
    private final Set<String> disabledWorlds;
    private final int diarySize;
    private final List<String> autoWolfNames;
    private final List<String> autoCatNames;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private PetsConfig(boolean enabled, String locale, int refreshTicks, int saveIntervalSeconds,
                       double interactRadius, double xpRadius, double ownerRadius, int topSize,
                       int maxPets, PetCurve.Params curve, Xp xp, Map<PetSpecies, Species> species,
                       int minNameLength, int maxNameLength, boolean showLevel, int keepDeadDays,
                       boolean careEnabled, PetCare.Settings care, Tricks tricks,
                       Movement movement, Cosmetics cosmetics, Bonuses bonuses,
                       Set<String> disabledWorlds, int diarySize,
                       List<String> autoWolfNames, List<String> autoCatNames) {
        this.enabled = enabled;
        this.locale = locale;
        this.refreshTicks = refreshTicks;
        this.saveIntervalSeconds = saveIntervalSeconds;
        this.interactRadius = interactRadius;
        this.xpRadius = xpRadius;
        this.ownerRadius = ownerRadius;
        this.topSize = topSize;
        this.maxPets = maxPets;
        this.curve = curve;
        this.xp = xp;
        this.species = species;
        this.minNameLength = minNameLength;
        this.maxNameLength = maxNameLength;
        this.showLevel = showLevel;
        this.keepDeadDays = keepDeadDays;
        this.careEnabled = careEnabled;
        this.care = care;
        this.tricks = tricks;
        this.movement = movement;
        this.cosmetics = cosmetics;
        this.bonuses = bonuses;
        this.disabledWorlds = disabledWorlds;
        this.diarySize = diarySize;
        this.autoWolfNames = autoWolfNames;
        this.autoCatNames = autoCatNames;
    }

    public static PetsConfig parse(FileConfiguration config, Logger logger) {
        ConfigurationSection general = section(config, "general");
        ConfigurationSection levels = section(config, "levels");
        ConfigurationSection xp = section(config, "xp");
        ConfigurationSection names = section(config, "names");
        ConfigurationSection storage = section(config, "storage");
        ConfigurationSection careSection = section(config, "care");
        ConfigurationSection tricksSection = section(config, "tricks");
        ConfigurationSection movementSection = section(config, "movement");
        ConfigurationSection cosmeticsSection = section(config, "cosmetics");
        ConfigurationSection diarySection = section(config, "diary");

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

        PetCare.Settings care = new PetCare.Settings(
                careSection.getInt("start-satiety", 70),
                careSection.getInt("feed-satiety", 35),
                careSection.getInt("feed-mood", 10),
                careSection.getInt("pat-mood", 6),
                xp.getInt("per-feed", 6),
                xp.getInt("per-pat", 2),
                careSection.getInt("satiety-decay-per-hour", 8),
                careSection.getInt("mood-decay-per-hour", 5),
                careSection.getInt("mood-neutral", 50),
                careSection.getInt("feed-cooldown-seconds", 45),
                careSection.getInt("pat-cooldown-seconds", 30),
                careSection.getInt("heal-cooldown-seconds", 120));

        Tricks tricks = new Tricks(
                clamp(tricksSection.getDouble("learn-chance-base", 0.35), 0.0, 1.0),
                clamp(tricksSection.getDouble("learn-chance-per-mood", 0.005), 0.0, 0.02),
                (int) clamp(tricksSection.getInt("cooldown-seconds", 60), 0L, 86_400L));

        Movement movement = new Movement(
                (int) clamp(movementSection.getInt("call-cooldown-seconds", 60), 0L, 86_400L),
                (int) clamp(movementSection.getInt("call-combat-block-seconds", 10), 0L, 600L),
                (int) clamp(movementSection.getInt("rename-cooldown-seconds", 60), 0L, 86_400L));

        Cosmetics cosmetics = new Cosmetics(
                cosmeticsSection.getBoolean("collar-enabled", true),
                cosmeticsSection.getBoolean("glow-enabled", true));

        double nearBonus = clamp(xp.getDouble("near-bonus", 1.10), 1.0, 3.0);
        double nearRadius = clamp(xp.getDouble("near-radius", 4.0), 1.0, 32.0);
        double nightBonus = clamp(xp.getDouble("night-bonus", 1.25), 1.0, 3.0);
        boolean nightWolfOnly = xp.getBoolean("night-wolf-only", true);
        double weekendBonus = clamp(xp.getDouble("weekend-bonus", 1.25), 1.0, 3.0);
        Set<DayOfWeek> weekendDays = parseDays(xp.getStringList("weekend-days"), logger);
        Bonuses bonuses = new Bonuses(nearBonus, nearRadius, nightBonus, nightWolfOnly,
                weekendBonus, weekendDays);

        Set<String> disabledWorlds = new LinkedHashSet<>();
        for (String world : xp.getStringList("disabled-worlds")) {
            if (world != null && !world.isBlank()) {
                disabledWorlds.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }

        int diarySize = (int) clamp(diarySection.getInt("size", 8), 0L, 50L);

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
                (int) clamp(general.getInt("max-pets", 0), 0L, 100L),
                curve, xpSettings, species,
                minName, maxName,
                names.getBoolean("show-level", true),
                (int) clamp(storage.getInt("keep-dead-days", 30), 0L, 3650L),
                careSection.getBoolean("enabled", true),
                care, tricks, movement, cosmetics, bonuses, disabledWorlds, diarySize,
                names.getStringList("auto-wolf"),
                names.getStringList("auto-cat"));
    }

    private static Set<DayOfWeek> parseDays(List<String> raw, Logger logger) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                if (logger != null) {
                    logger.warning("pets: неизвестный день недели в xp.weekend-days: " + value);
                }
            }
        }
        return Set.copyOf(days);
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

    /** Лимит живых питомцев на игрока; 0 — без лимита. */
    public int maxPets() {
        return maxPets;
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

    public boolean careEnabled() {
        return careEnabled;
    }

    public PetCare.Settings care() {
        return care;
    }

    public Tricks tricks() {
        return tricks;
    }

    public Movement movement() {
        return movement;
    }

    public Cosmetics cosmetics() {
        return cosmetics;
    }

    public Bonuses bonuses() {
        return bonuses;
    }

    /** Миры, в которых опыт не начисляется (имена в нижнем регистре). */
    public Set<String> disabledWorlds() {
        return disabledWorlds;
    }

    public int diarySize() {
        return diarySize;
    }

    /** Пул автоимён для вида; пустой список — автоимя выключено. */
    public List<String> autoNames(PetSpecies species) {
        if (species == PetSpecies.CAT) {
            return autoCatNames;
        }
        if (species == PetSpecies.WOLF) {
            return autoWolfNames;
        }
        return List.of();
    }
}
