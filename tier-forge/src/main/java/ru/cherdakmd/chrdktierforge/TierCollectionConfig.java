package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
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
 * перезапуске.</p>
 *
 * <p>Награды делятся на два слоя. <b>Вехи</b> — промежуточные рубежи
 * (15/45/75 свойств, 10/20 аффиксов). <b>Разделы</b> — полностью закрытые
 * полки: все семь редкостей, все 105 свойств, все 30 аффиксов и вся кузня
 * целиком. За закрытый раздел платится крупнее и выдаётся титул — он
 * надевается рядом с ником в чате через {@code NameTagProvider} ядра.</p>
 *
 * <p>Награды настраиваются, чтобы сервер мог выбрать свою экономику. Пустая
 * секция {@code collection.rewards.properties: {}} означает «за свойства
 * ничего не давать», а отсутствующая — умолчания. Значения клампятся:
 * проходка 0..365 дней, алмазы 0..576.</p>
 *
 * @param enabled       включена ли коллекция (выключение не стирает книгу)
 * @param announceFinds объявлять в чат каждую новую находку
 * @param titles        показывать заработанные титулы рядом с ником в чате
 * @param properties    вехи по числу открытых свойств, по возрастанию
 * @param affixes       вехи по числу открытых аффиксов, по возрастанию
 * @param sections      награды за полностью закрытые разделы
 */
public record TierCollectionConfig(boolean enabled, boolean announceFinds, boolean titles,
                                   List<Milestone> properties,
                                   List<Milestone> affixes,
                                   Map<String, SectionReward> sections) {

    /** Веха: сколько единиц собрать и что за это дать. */
    public record Milestone(int count, int passDays, int diamonds) {

        public boolean hasReward() {
            return passDays > 0 || diamonds > 0;
        }
    }

    /** Награда за закрытый раздел; титул берётся из {@code messages.title.<id>}. */
    public record SectionReward(int passDays, int diamonds) {

        public boolean hasReward() {
            return passDays > 0 || diamonds > 0;
        }
    }

    /** Разделы в порядке показа: от малого к целому. */
    public static final List<String> SECTIONS = List.of("tiers", "affixes", "properties", "all");

    /** Сколько всего свойств в каталоге (105). */
    public static final int PROPERTY_TOTAL = TierProperties.all().size();

    /** Сколько всего аффиксов в каталоге (30). */
    public static final int AFFIX_TOTAL = TierAffixes.all().size();

    /** Умолчания: включено, объявлять находки, титулы, три вехи и четыре раздела. */
    public static final TierCollectionConfig DEFAULT = new TierCollectionConfig(
            true, true, true,
            List.of(new Milestone(15, 2, 8),
                    new Milestone(45, 4, 16),
                    new Milestone(75, 7, 32)),
            List.of(new Milestone(10, 2, 8),
                    new Milestone(20, 4, 16)),
            defaultSections());

    /** Умолчания разделов в порядке показа: Map.of() порядок не гарантирует. */
    private static Map<String, SectionReward> defaultSections() {
        Map<String, SectionReward> map = new LinkedHashMap<>();
        map.put("tiers", new SectionReward(3, 16));
        map.put("affixes", new SectionReward(5, 24));
        map.put("properties", new SectionReward(8, 48));
        map.put("all", new SectionReward(15, 96));
        return Collections.unmodifiableMap(map);
    }

    public TierCollectionConfig {
        properties = List.copyOf(properties == null ? List.of() : properties);
        affixes = List.copyOf(affixes == null ? List.of() : affixes);
        // Порядок разделов важен для показа — Map.copyOf его не гарантирует.
        sections = sections == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }

    /** Разбор секции {@code collection.*}; кривые значения чинятся с предупреждением. */
    public static TierCollectionConfig parse(FileConfiguration config, Logger logger) {
        if (config == null) {
            return DEFAULT;
        }
        boolean enabled = config.getBoolean("collection.enabled", true);
        boolean announce = config.getBoolean("collection.announce-finds", true);
        boolean titles = config.getBoolean("collection.titles.enabled", true);
        List<Milestone> properties = parseMilestones(
                config.getConfigurationSection("collection.rewards.properties"),
                logger, "collection.rewards.properties", PROPERTY_TOTAL, DEFAULT.properties);
        List<Milestone> affixes = parseMilestones(
                config.getConfigurationSection("collection.rewards.affixes"),
                logger, "collection.rewards.affixes", AFFIX_TOTAL, DEFAULT.affixes);
        Map<String, SectionReward> sections = parseSections(
                config.getConfigurationSection("collection.rewards.sections"), logger);
        return new TierCollectionConfig(enabled, announce, titles, properties, affixes, sections);
    }

    /** Награда раздела; неизвестный ид — «без награды», а не {@code null}. */
    public SectionReward section(String id) {
        SectionReward reward = id == null ? null : sections.get(id);
        return reward == null ? new SectionReward(0, 0) : reward;
    }

    /**
     * Порог закрытия раздела: 7 редкостей, 30 аффиксов, 105 свойств или весь
     * каталог (142).
     */
    public static int threshold(String section) {
        if (section == null) {
            return 0;
        }
        return switch (section) {
            case "tiers" -> TierDef.LADDER.size();
            case "affixes" -> AFFIX_TOTAL;
            case "properties" -> PROPERTY_TOTAL;
            case "all" -> TierDef.LADDER.size() + PROPERTY_TOTAL + AFFIX_TOTAL;
            default -> 0;
        };
    }

    /** Разбор наград за разделы; отсутствующий ключ — умолчание, нули — «без награды». */
    private static Map<String, SectionReward> parseSections(ConfigurationSection section,
                                                           Logger logger) {
        Map<String, SectionReward> out = new LinkedHashMap<>();
        for (String id : SECTIONS) {
            SectionReward fallback = DEFAULT.sections.getOrDefault(id, new SectionReward(0, 0));
            ConfigurationSection row = section == null ? null : section.getConfigurationSection(id);
            if (row == null) {
                out.put(id, fallback);
                continue;
            }
            int passDays = clamp(row.getInt("pass-days", fallback.passDays()), 0, 365, logger,
                    "collection.rewards.sections." + id + ".pass-days");
            int diamonds = clamp(row.getInt("diamonds", fallback.diamonds()), 0, 576, logger,
                    "collection.rewards.sections." + id + ".diamonds");
            out.put(id, new SectionReward(passDays, diamonds));
        }
        return Collections.unmodifiableMap(out);
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
