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
 * ранг 0..24 часов (потолок — сутки), алмазы 0..576.</p>
 *
 * @param enabled       включена ли коллекция (выключение не стирает книгу)
 * @param announceFinds объявлять в чат каждую новую находку
 * @param titles        показ заработанных титулов (чат через ядро и таб-лист)
 * @param properties    вехи по числу открытых свойств, по возрастанию
 * @param affixes       вехи по числу открытых аффиксов, по возрастанию
 * @param sections      награды за полностью закрытые разделы
 */
public record TierCollectionConfig(boolean enabled, boolean announceFinds, Titles titles,
                                   List<Milestone> properties,
                                   List<Milestone> affixes,
                                   Map<String, SectionReward> sections) {

    /**
     * Веха: сколько единиц собрать и что за это дать.
     *
     * @param passHours часы ранга (с 1.4.184 награда измеряется часами, потолок — сутки)
     */
    public record Milestone(int count, int passHours, int diamonds) {

        public boolean hasReward() {
            return passHours > 0 || diamonds > 0;
        }
    }

    /**
     * Титулы: общий выключатель и отдельная настройка таб-листа.
     *
     * <p>В чате титул рисует ядро (оформление ников), а таб-лист аддон ведёт
     * сам — но только если ядро не задаёт свой формат имени (см.
     * {@code tablist.player-name-format}). Иначе титул попадает в таб через
     * ядро: {@code %player_tagged%} уже содержит метки аддонов.</p>
     *
     * @param enabled         показывать титулы вообще
     * @param tab             вести имя в таб-листе самим
     * @param tabFormat       шаблон имени: {@code %player%} и {@code %title%}
     * @param tabRefreshTicks период обновления таба (20 тиков = 1 сек)
     */
    public record Titles(boolean enabled, boolean tab, String tabFormat, int tabRefreshTicks) {

        /** Шаблон по умолчанию: «ник · титул». */
        public static final String DEFAULT_FORMAT =
                "<white>%player%</white> <dark_gray>·</dark_gray> %title%";

        public static final Titles DEFAULT = new Titles(true, true, DEFAULT_FORMAT, 60);

        public Titles {
            // Пустой шаблон — «не трогать имена»: иначе таб потеряет ник.
            if (tabFormat == null || tabFormat.isBlank()) {
                tabFormat = DEFAULT_FORMAT;
            }
        }
    }

    /**
     * Награда за закрытый раздел; титул берётся из {@code messages.title.<id>}.
     *
     * @param passHours часы ранга (с 1.4.184 награда измеряется часами, потолок — сутки)
     */
    public record SectionReward(int passHours, int diamonds) {

        public boolean hasReward() {
            return passHours > 0 || diamonds > 0;
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
            true, true, Titles.DEFAULT,
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
        // null-секция титулов — умолчания: старые конфиги и тесты не должны падать.
        titles = titles == null ? Titles.DEFAULT : titles;
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
        Titles titles = parseTitles(config, logger);
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

    /** Секция {@code collection.titles.*}: показ в чате и настройка таб-листа. */
    private static Titles parseTitles(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("collection.titles.enabled", true);
        boolean tab = config.getBoolean("collection.titles.tab.enabled", true);
        String format = config.getString("collection.titles.tab.name-format",
                Titles.DEFAULT_FORMAT);
        int ticks = clamp(config.getInt("collection.titles.tab.refresh-ticks",
                        Titles.DEFAULT.tabRefreshTicks()), 20, 1200, logger,
                "collection.titles.tab.refresh-ticks");
        if (format == null || format.isBlank()) {
            loggerWarning(logger, "collection.titles.tab.name-format",
                    "пустой шаблон — имена в табе не трогаем");
            format = Titles.DEFAULT_FORMAT;
        }
        if (!format.contains("%player%")) {
            loggerWarning(logger, "collection.titles.tab.name-format",
                    "нет %player% — ник потеряется, добавляю его в начало");
            format = "%player% " + format;
        }
        return new Titles(enabled, tab, format, ticks);
    }

    private static void loggerWarning(Logger logger, String key, String message) {
        if (logger != null) {
            logger.warning("config: " + key + " — " + message);
        }
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
            // 1.4.184: часы вместо суток; старый ключ pass-days читается как часы.
            String hoursKey = row.contains("pass-hours") ? "pass-hours" : "pass-days";
            int passHours = clamp(row.getInt(hoursKey, fallback.passHours()), 0, 24, logger,
                    "collection.rewards.sections." + id + "." + hoursKey);
            int diamonds = clamp(row.getInt("diamonds", fallback.diamonds()), 0, 576, logger,
                    "collection.rewards.sections." + id + ".diamonds");
            out.put(id, new SectionReward(passHours, diamonds));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Разбор карты «число → награда». Ключ — целое число (15, 45, …), значения —
     * {@code pass-hours} и {@code diamonds}. Секция без ключей означает «вех нет».
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
            String hoursKey = row != null && row.contains("pass-hours") ? "pass-hours" : "pass-days";
            int passHours = clamp(row == null ? 0 : row.getInt(hoursKey, 0), 0, 24, logger,
                    key + "." + raw + "." + hoursKey);
            int diamonds = clamp(row == null ? 0 : row.getInt("diamonds", 0), 0, 576, logger,
                    key + "." + raw + ".diamonds");
            byCount.put(count, new Milestone(count, passHours, diamonds));
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
