package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Секция {@code collection.*}: коллекция кузни (1.4.150).
 *
 * <p>Игрок собирает не предметы, а <b>впечатления</b>: семь редкостей, 105
 * уникальных свойств тиров (по 15 на тир) и 30 аффиксов (15 хороших и 15
 * плохих). Каждое первое появление отмечается в книге и не теряется при
 * перезапуске. За вехи (15/45/75/105 свойств, 10/20/30 аффиксов и все семь
 * редкостей) начисляются дни проходки и алмазы — раз и навсегда.</p>
 *
 * <p>Награды настраиваются, чтобы сервер мог выбрать свою экономику. Пустая
 * секция {@code collection.rewards.properties: {}} означает «за свойства
 * ничего не давать», а отсутствующая — умолчания. Значения клампятся:
 * проходка 0..365 дней, алмазы 0..576.</p>
 *
 * @param enabled       включена ли коллекция (выключение не стирает книгу)
 * @param announceFinds объявлять в чат каждую новую находку
 * @param tiers         веха «все семь редкостей»
 * @param properties    вехи по числу открытых свойств, по возрастанию
 * @param affixes       вехи по числу открытых аффиксов, по возрастанию
 */
public record TierCollectionConfig(boolean enabled, boolean announceFinds,
                                   Milestone tiers,
                                   List<Milestone> properties,
                                   List<Milestone> affixes) {

    /** Веха: сколько единиц собрать и что за это дать. */
    public record Milestone(int count, int passDays, int diamonds) {

        public boolean hasReward() {
            return passDays > 0 || diamonds > 0;
        }
    }

    /** Сколько всего свойств в каталоге (105). */
    public static final int PROPERTY_TOTAL = TierProperties.all().size();

    /** Сколько всего аффиксов в каталоге (30). */
    public static final int AFFIX_TOTAL = TierAffixes.all().size();

    /** Умолчания: включено, объявлять находки, награды за четыре рубежа. */
    public static final TierCollectionConfig DEFAULT = new TierCollectionConfig(
            true, true,
            new Milestone(TierDef.LADDER.size(), 3, 16),
            List.of(new Milestone(15, 2, 8),
                    new Milestone(45, 4, 16),
                    new Milestone(75, 7, 32),
                    new Milestone(105, 12, 64)),
            List.of(new Milestone(10, 2, 8),
                    new Milestone(20, 4, 16),
                    new Milestone(30, 6, 24)));

    public TierCollectionConfig {
        properties = List.copyOf(properties == null ? List.of() : properties);
        affixes = List.copyOf(affixes == null ? List.of() : affixes);
        if (tiers == null) {
            tiers = DEFAULT == null ? new Milestone(TierDef.LADDER.size(), 3, 16) : DEFAULT.tiers;
        }
    }

    /** Разбор секции {@code collection.*}; кривые значения чинятся с предупреждением. */
    public static TierCollectionConfig parse(FileConfiguration config, Logger logger) {
        if (config == null) {
            return DEFAULT;
        }
        boolean enabled = config.getBoolean("collection.enabled", true);
        boolean announce = config.getBoolean("collection.announce-finds", true);
        Milestone tiers = parseTiers(config.getConfigurationSection("collection.rewards.tiers"),
                logger, DEFAULT.tiers);
        List<Milestone> properties = parseMilestones(
                config.getConfigurationSection("collection.rewards.properties"),
                logger, "collection.rewards.properties", PROPERTY_TOTAL, DEFAULT.properties);
        List<Milestone> affixes = parseMilestones(
                config.getConfigurationSection("collection.rewards.affixes"),
                logger, "collection.rewards.affixes", AFFIX_TOTAL, DEFAULT.affixes);
        return new TierCollectionConfig(enabled, announce, tiers, properties, affixes);
    }

    /** Веха «все редкости»: порог фиксирован каталогом, настраивается только награда. */
    private static Milestone parseTiers(ConfigurationSection section, Logger logger,
                                        Milestone fallback) {
        if (section == null) {
            return fallback;
        }
        int passDays = clamp(section.getInt("pass-days", fallback.passDays()), 0, 365, logger,
                "collection.rewards.tiers.pass-days");
        int diamonds = clamp(section.getInt("diamonds", fallback.diamonds()), 0, 576, logger,
                "collection.rewards.tiers.diamonds");
        return new Milestone(TierDef.LADDER.size(), passDays, diamonds);
    }

    /**
     * Разбор карты «число → награда». Ключ — целое число (15, 45, …), значения —
     * {@code pass-days} и {@code diamonds}. Секция без ключей означает «вех нет».
     */
    private static List<Milestone> parseMilestones(ConfigurationSection section, Logger logger,
                                                   String key, int maxCount,
                                                   List<Milestone> fallback) {
        if (section == null) {
            return fallback;
        }
        Map<Integer, Milestone> byCount = new LinkedHashMap<>();
        for (String raw : section.getKeys(false)) {
            int count;
            try {
                count = Integer.parseInt(raw.trim());
            } catch (NumberFormatException exception) {
                if (logger != null) {
                    logger.warning("config: " + key + "." + raw + " — ключ не число, пропускаю");
                }
                continue;
            }
            if (count < 1 || count > maxCount) {
                if (logger != null) {
                    logger.warning("config: " + key + "." + raw + " вне диапазона 1.."
                            + maxCount + ", пропускаю");
                }
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(raw);
            int passDays = clamp(row == null ? 0 : row.getInt("pass-days", 0), 0, 365, logger,
                    key + "." + raw + ".pass-days");
            int diamonds = clamp(row == null ? 0 : row.getInt("diamonds", 0), 0, 576, logger,
                    key + "." + raw + ".diamonds");
            byCount.put(count, new Milestone(count, passDays, diamonds));
        }
        List<Milestone> out = new ArrayList<>(byCount.values());
        out.sort(Comparator.comparingInt(Milestone::count));
        return List.copyOf(out);
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            if (logger != null) {
                logger.warning("config: " + key + " вне диапазона " + min + ".." + max
                        + ", применена граница");
            }
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
