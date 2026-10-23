package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} доски заказов.
 *
 * <p>Все числа клампятся здесь: админ, поставивший {@code board.size: 500},
 * увидит в логе предупреждение и получит 14 заказов, а не меню на полтысячи
 * слотов. Материалы ищутся через {@link Materials#resolve(String)} — 
 * опечатка в конфиге пропускается с предупреждением, а не роняет плагин.</p>
 */
public final class OrdersConfig {

    /** Сколько заказов влезает в меню: две строки по семь слотов. */
    public static final int MAX_BOARD_SIZE = 14;

    private final boolean enabled;
    private final String locale;
    private final int topSize;
    private final int boardSize;
    private final int orderHours;
    private final double premium;
    private final int jitterPercent;
    private final int minReward;
    private final int maxReward;
    private final int maxPerDay;
    private final int cooldownSeconds;
    /** Порог репутации → ид звания, по возрастанию. */
    private final TreeMap<Integer, String> ranks;
    private final List<OrderBot> bots;
    private final Map<String, OrderCategory> categories;

    private OrdersConfig(boolean enabled, String locale, int topSize, int boardSize,
                         int orderHours, double premium, int jitterPercent, int minReward,
                         int maxReward, int maxPerDay, int cooldownSeconds,
                         TreeMap<Integer, String> ranks, List<OrderBot> bots,
                         Map<String, OrderCategory> categories) {
        this.enabled = enabled;
        this.locale = locale;
        this.topSize = topSize;
        this.boardSize = boardSize;
        this.orderHours = orderHours;
        this.premium = premium;
        this.jitterPercent = jitterPercent;
        this.minReward = minReward;
        this.maxReward = maxReward;
        this.maxPerDay = maxPerDay;
        this.cooldownSeconds = cooldownSeconds;
        this.ranks = ranks;
        this.bots = bots;
        this.categories = categories;
    }

    /* --------------------------------- разбор --------------------------------- */

    public static OrdersConfig parse(FileConfiguration config, Logger logger) {
        if (config == null) {
            config = new org.bukkit.configuration.file.YamlConfiguration();
        }
        boolean enabled = config.getBoolean("general.enabled", true);
        String locale = config.getString("general.locale", "ru");
        long rawTop = config.getLong("general.top-size", 10L);
        int topSize = (int) clamp(rawTop, 3L, 50L);
        warnClamped(logger, "general.top-size", rawTop, topSize);

        long rawBoardSize = config.getLong("board.size", 7L);
        int boardSize = (int) clamp(rawBoardSize, 1L, MAX_BOARD_SIZE);
        warnClamped(logger, "board.size", rawBoardSize, boardSize);

        long rawOrderHours = config.getLong("board.order-hours", 8L);
        int orderHours = (int) clamp(rawOrderHours, 1L, 168L);
        warnClamped(logger, "board.order-hours", rawOrderHours, orderHours);

        double rawPremium = config.getDouble("rewards.premium", 1.25);
        double premium = clamp(rawPremium, 0.5, 3.0);
        if (Math.abs(rawPremium - premium) > 1.0e-9) {
            warnClamped(logger, "rewards.premium", rawPremium, premium);
        }

        long rawJitter = config.getLong("rewards.jitter-percent", 20L);
        int jitter = (int) clamp(rawJitter, 0L, 50L);
        warnClamped(logger, "rewards.jitter-percent", rawJitter, jitter);

        long rawMin = config.getLong("rewards.min", 1L);
        int minReward = (int) clamp(rawMin, 1L, 576L);
        warnClamped(logger, "rewards.min", rawMin, minReward);

        long rawMax = config.getLong("rewards.max", 64L);
        int maxReward = (int) clamp(rawMax, minReward, 576L);
        warnClamped(logger, "rewards.max", rawMax, maxReward);

        long rawMaxPerDay = config.getLong("limits.max-per-day", 20L);
        int maxPerDay = (int) clamp(rawMaxPerDay, 0L, 500L);
        warnClamped(logger, "limits.max-per-day", rawMaxPerDay, maxPerDay);

        long rawCooldown = config.getLong("limits.cooldown-seconds", 30L);
        int cooldown = (int) clamp(rawCooldown, 0L, 3600L);
        warnClamped(logger, "limits.cooldown-seconds", rawCooldown, cooldown);

        TreeMap<Integer, String> ranks = parseRanks(config, logger);
        List<OrderBot> bots = parseBots(config, logger);
        Map<String, OrderCategory> categories = parseCategories(config, logger);

        if (enabled && (bots.isEmpty() || categories.isEmpty())) {
            if (logger != null) {
                logger.warning("[CHRDK_Orders] В config.yml нет рабочих ботов или категорий — "
                        + "доска будет пустой. Проверьте секции bots и categories.");
            }
        }
        return new OrdersConfig(enabled, locale, topSize, boardSize, orderHours, premium,
                jitter, minReward, maxReward, maxPerDay, cooldown,
                ranks, bots, Collections.unmodifiableMap(categories));
    }

    private static TreeMap<Integer, String> parseRanks(FileConfiguration config, Logger logger) {
        TreeMap<Integer, String> out = new TreeMap<>();
        ConfigurationSection section = config.getConfigurationSection("reputation.ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Integer threshold = parseInt(key);
                String id = section.getString(key, "");
                if (threshold == null || threshold < 0 || id == null || id.isBlank()) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Orders] reputation.ranks." + key
                                + ": нужен неотрицательный порог и имя звания — пропускаю.");
                    }
                    continue;
                }
                out.put(threshold, id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? new TreeMap<>(defaultRanks()) : out;
    }

    private static List<OrderBot> parseBots(FileConfiguration config, Logger logger) {
        List<OrderBot> out = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("bots");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            String name = section.getString(key + ".name", key);
            List<String> categoryIds = new ArrayList<>();
            for (String raw : section.getStringList(key + ".categories")) {
                if (raw != null && !raw.isBlank()) {
                    categoryIds.add(raw.trim().toLowerCase(Locale.ROOT));
                }
            }
            List<String> thanks = new ArrayList<>();
            for (String raw : section.getStringList(key + ".thanks")) {
                if (raw != null && !raw.isBlank()) {
                    thanks.add(raw.trim());
                }
            }
            if (categoryIds.isEmpty()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Orders] bots." + key + ": нет категорий — бот пропущен.");
                }
                continue;
            }
            out.add(new OrderBot(key, name, categoryIds, thanks));
        }
        return out;
    }

    private static Map<String, OrderCategory> parseCategories(FileConfiguration config,
                                                              Logger logger) {
        Map<String, OrderCategory> out = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("categories");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            double weight = clamp(section.getDouble(key + ".weight", 0.0), 0.0, 1000.0);
            int minReputation = (int) clamp(section.getLong(key + ".min-reputation", 0L),
                    0L, 100_000L);
            int reputation = (int) clamp(section.getLong(key + ".reputation", 1L), 0L, 100L);
            double passChance = clamp(section.getDouble(key + ".pass-days-chance", 0.0),
                    0.0, 100.0);
            int passDays = (int) clamp(section.getLong(key + ".pass-days", 0L),
                    0L, OrderCategory.MAX_PASS_DAYS);
            List<OrderItem> items = new ArrayList<>();
            ConfigurationSection itemsSection =
                    section.getConfigurationSection(key + ".items");
            if (itemsSection != null) {
                for (String materialKey : itemsSection.getKeys(false)) {
                    Material material = Materials.resolve(materialKey);
                    if (material == null || material.isAir()) {
                        if (logger != null) {
                            logger.warning("[CHRDK_Orders] categories." + key + ".items."
                                    + materialKey + ": неизвестный материал — пропускаю.");
                        }
                        continue;
                    }
                    int minAmount = itemsSection.getInt(materialKey + ".amount-min", 1);
                    int maxAmount = itemsSection.getInt(materialKey + ".amount-max", minAmount);
                    double rewardPerItem = itemsSection.getDouble(materialKey + ".reward-per-item", 0.0);
                    items.add(new OrderItem(material, minAmount, maxAmount, rewardPerItem));
                }
            }
            if (weight <= 0.0 || items.isEmpty()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Orders] categories." + key
                            + ": нулевой вес или пустой список материалов — категория пропущена.");
                }
                continue;
            }
            out.put(key.toLowerCase(Locale.ROOT),
                    new OrderCategory(key, weight, minReputation, reputation,
                            passChance, passDays, items));
        }
        return out;
    }

    /* --------------------------------- доступ --------------------------------- */

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public int topSize() {
        return topSize;
    }

    public int boardSize() {
        return boardSize;
    }

    public int orderHours() {
        return orderHours;
    }

    /** Базовый срок жизни заказа в миллисекундах. */
    public long orderTtlMillis() {
        return orderHours * 3_600_000L;
    }

    public double premium() {
        return premium;
    }

    public int jitterPercent() {
        return jitterPercent;
    }

    public int minReward() {
        return minReward;
    }

    public int maxReward() {
        return maxReward;
    }

    public int maxPerDay() {
        return maxPerDay;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public List<OrderBot> bots() {
        return List.copyOf(bots);
    }

    public Map<String, OrderCategory> categories() {
        return categories;
    }

    public OrderCategory category(String id) {
        return id == null ? null : categories.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Звание по репутации; ниже минимального порога — первое звание списка. */
    public String rankId(int reputation) {
        Map.Entry<Integer, String> entry = ranks.floorEntry(Math.max(0, reputation));
        if (entry != null) {
            return entry.getValue();
        }
        return ranks.isEmpty() ? "novice" : ranks.firstEntry().getValue();
    }

    /** Следующий порог репутации; {@code -1} — звание уже высшее. */
    public int nextRankThreshold(int reputation) {
        Integer next = ranks.higherKey(Math.max(0, reputation));
        return next == null ? -1 : next;
    }

    /** Ид следующего звания; {@code null} — выше некуда. */
    public String nextRankId(int reputation) {
        Integer next = ranks.higherKey(Math.max(0, reputation));
        return next == null ? null : ranks.get(next);
    }

    /** Порог последнего звания — для проверки «всё пройдено». */
    public int lastRankThreshold() {
        return ranks.isEmpty() ? 0 : ranks.lastKey();
    }

    /** Звания по умолчанию: работают, даже если секции в конфиге нет. */
    public static Map<Integer, String> defaultRanks() {
        Map<Integer, String> out = new LinkedHashMap<>();
        out.put(0, "novice");
        out.put(5, "trusted");
        out.put(15, "regular");
        out.put(30, "partner");
        out.put(60, "master");
        return out;
    }

    private static void warnClamped(Logger logger, String path, long raw, long actual) {
        if (logger != null && raw != actual) {
            logger.warning("[CHRDK_Orders] " + path + ": значение " + raw
                    + " вне допустимых границ — использую " + actual + ".");
        }
    }

    private static void warnClamped(Logger logger, String path, double raw, double actual) {
        if (logger != null && Math.abs(raw - actual) > 1.0e-9) {
            logger.warning("[CHRDK_Orders] " + path + ": значение " + raw
                    + " вне допустимых границ — использую " + actual + ".");
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }
}
