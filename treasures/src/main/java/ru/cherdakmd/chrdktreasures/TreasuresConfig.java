package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Разбор config.yml: шансы, блоки, прогрессия и лимиты.
 *
 * <p>Все числа клампятся: шанс выше 100 % не должен превращать каждый камень в
 * клад, а нулевой TTL — отключать защиту от «поставь-сломай». Набор блоков
 * задаётся секцией {@code blocks}: если её нет, используется встроенный список
 * (камень, глубинные породы, руды, незерит), а если есть — конфиг полностью
 * заменяет его, чтобы админ мог убрать лишнее.</p>
 *
 * <p>Запись блока бывает двух видов. Число — множитель базовых шансов
 * редкостей (старый формат). Секция с редкостями — точные проценты на блок
 * для каждой редкости, как в таблицах Mine Treasure; такие значения уже не
 * зависят от {@code chances.<редкость>.chance-percent} и умножаются только на
 * бонус глубины.</p>
 *
 * <p>Шансы редкостей можно переопределить файлом {@code chances.yml} (его
 * пишет {@code /treasuresadmin chance}); переопределение применяется поверх
 * {@code config.yml} и не трогает комментарии основного файла.</p>
 */
public final class TreasuresConfig {

    /** Награда за количество найденных именных предметов. */
    public record Milestone(int found, int diamonds) {
    }

    /** Оформление находки: заголовок, строка действия и фейерверк. */
    public record Effects(boolean titles, boolean actionbar, boolean fireworks,
                          TreasureTier titleMinTier, TreasureTier fireworkMinTier) {
    }

    private final boolean enabled;
    private final int auraRefreshTicks;
    private final int topSize;
    private final Map<TreasureTier, Double> chances;
    private final Map<TreasureTier, Double> itemChances;
    private final Map<String, Double> blocks;
    private final Map<String, Map<TreasureTier, Double>> blockTables;
    private final double depthPerBlock;
    private final double depthMaxBonus;
    private final long placedBlockTtlSeconds;
    private final int placedBlockMaxEntries;
    private final long cooldownMillis;
    private final Map<Integer, Milestone> milestones;
    private final boolean broadcastLegendary;
    private final boolean broadcastMythical;
    private final boolean broadcastLegend;
    private final int streakWindowSeconds;
    private final int streakBonusEvery;
    private final Map<TreasureRank, Integer> rankRewards;
    private final Effects effects;
    private final boolean abilitiesEnabled;
    private final boolean cursesEnabled;
    private final boolean setsEnabled;
    private final double arsenalShare;

    private TreasuresConfig(boolean enabled, int auraRefreshTicks, int topSize,
                            Map<TreasureTier, Double> chances,
                            Map<TreasureTier, Double> itemChances,
                            Map<String, Double> blocks,
                            Map<String, Map<TreasureTier, Double>> blockTables,
                            double depthPerBlock,
                            double depthMaxBonus, long placedBlockTtlSeconds,
                            int placedBlockMaxEntries, long cooldownMillis,
                            Map<Integer, Milestone> milestones,
                            boolean broadcastLegendary, boolean broadcastMythical,
                            boolean broadcastLegend, int streakWindowSeconds, int streakBonusEvery,
                            Map<TreasureRank, Integer> rankRewards, Effects effects,
                            boolean abilitiesEnabled, boolean cursesEnabled,
                            boolean setsEnabled, double arsenalShare) {
        this.enabled = enabled;
        this.auraRefreshTicks = auraRefreshTicks;
        this.topSize = topSize;
        this.chances = chances;
        this.itemChances = itemChances;
        this.blocks = blocks;
        this.blockTables = blockTables;
        this.depthPerBlock = depthPerBlock;
        this.depthMaxBonus = depthMaxBonus;
        this.placedBlockTtlSeconds = placedBlockTtlSeconds;
        this.placedBlockMaxEntries = placedBlockMaxEntries;
        this.cooldownMillis = cooldownMillis;
        this.milestones = milestones;
        this.broadcastLegendary = broadcastLegendary;
        this.broadcastMythical = broadcastMythical;
        this.broadcastLegend = broadcastLegend;
        this.streakWindowSeconds = streakWindowSeconds;
        this.streakBonusEvery = streakBonusEvery;
        this.rankRewards = rankRewards;
        this.effects = effects;
        this.abilitiesEnabled = abilitiesEnabled;
        this.cursesEnabled = cursesEnabled;
        this.setsEnabled = setsEnabled;
        this.arsenalShare = arsenalShare;
    }

    /** Разбор без переопределений: значения шансов берутся только из config.yml. */
    public static TreasuresConfig parse(FileConfiguration cfg, Logger logger) {
        return parse(cfg, Map.of(), logger);
    }

    /**
     * Разбор с переопределениями шансов из {@code chances.yml}.
     *
     * @param overrides шансы в процентах поверх config.yml; пустая карта — без правок
     */
    public static TreasuresConfig parse(FileConfiguration cfg,
                                        Map<TreasureTier, Double> overrides, Logger logger) {
        boolean enabled = cfg.getBoolean("general.enabled", true);
        int auraTicks = (int) clamp(cfg.getLong("general.aura-refresh-ticks", 60L), 20L, 200L);
        int topSize = (int) clamp(cfg.getLong("general.top-size", 10L), 3L, 50L);

        Map<TreasureTier, Double> chances = new EnumMap<>(TreasureTier.class);
        Map<TreasureTier, Double> itemChances = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            double chance = clamp(cfg.getDouble("chances." + tier.id() + ".chance-percent",
                    tier.defaultChancePercent()), 0.0, 100.0);
            double item = clamp(cfg.getDouble("chances." + tier.id() + ".item-chance",
                    tier.defaultItemChance()), 0.0, 1.0);
            Double override = overrides == null ? null : overrides.get(tier);
            if (override != null && !override.isNaN()) {
                chance = clamp(override, 0.0, 100.0);
            }
            chances.put(tier, chance);
            itemChances.put(tier, item);
        }

        Map<String, Double> blocks = new LinkedHashMap<>();
        Map<String, Map<TreasureTier, Double>> blockTables = new LinkedHashMap<>();
        ConfigurationSection blockRoot = cfg.getConfigurationSection("blocks");
        if (blockRoot != null) {
            for (String key : blockRoot.getKeys(false)) {
                String material = key.trim().toUpperCase(java.util.Locale.ROOT);
                if (blockRoot.isConfigurationSection(key)) {
                    ConfigurationSection table = blockRoot.getConfigurationSection(key);
                    Map<TreasureTier, Double> values = new EnumMap<>(TreasureTier.class);
                    boolean positive = false;
                    for (TreasureTier tier : TreasureTier.values()) {
                        double percent = clamp(table.getDouble(tier.id(), 0.0), 0.0, 100.0);
                        values.put(tier, percent);
                        positive |= percent > 0.0;
                    }
                    if (positive) {
                        blockTables.put(material, Collections.unmodifiableMap(values));
                    }
                    continue;
                }
                double multiplier = clamp(blockRoot.getDouble(key, 1.0), 0.0, 100.0);
                if (multiplier > 0.0) {
                    blocks.put(material, multiplier);
                }
            }
        }
        if (blocks.isEmpty() && blockTables.isEmpty()) {
            blocks = defaultBlocks();
        }

        double depthPerBlock = clamp(cfg.getDouble("depth.per-block-bonus", 0.002), 0.0, 0.1);
        double depthMax = clamp(cfg.getDouble("depth.max-bonus", 0.5), 0.0, 10.0);

        long ttl = clamp(cfg.getLong("limits.placed-block-seconds", 300L), 5L, 86_400L);
        int maxEntries = (int) clamp(cfg.getLong("limits.placed-block-max", 20_000L),
                64L, 1_000_000L);
        long cooldown = clamp(cfg.getLong("limits.min-seconds-between-treasures", 3L),
                0L, 3_600L) * 1000L;

        double arsenalShare = clamp(cfg.getDouble("arsenal.share", 0.35), 0.0, 1.0);

        Map<Integer, Milestone> milestones = new TreeMap<>();
        ConfigurationSection milestoneRoot = cfg.getConfigurationSection("milestones");
        if (milestoneRoot != null) {
            for (String key : milestoneRoot.getKeys(false)) {
                Integer found = parseInt(key);
                if (found == null || found < 1 || found > TreasureRegistry.size()) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Treasures] milestones." + key
                                + ": ключ должен быть числом 1.." + TreasureRegistry.size()
                                + " — пропускаю.");
                    }
                    continue;
                }
                int diamonds = (int) clamp(milestoneRoot.getInt(key + ".diamonds", 0), 0, 576);
                milestones.put(found, new Milestone(found, diamonds));
            }
        }
        if (milestones.isEmpty()) {
            milestones = new TreeMap<>(defaultMilestones());
        }

        boolean broadcastLegendary = cfg.getBoolean("feedback.broadcast-legendary", true);
        boolean broadcastMythical = cfg.getBoolean("feedback.broadcast-mythical", true);
        boolean broadcastLegend = cfg.getBoolean("feedback.broadcast-legend", true);

        int streakWindow = (int) clamp(cfg.getLong("streak.window-seconds", 600L),
                10L, 86_400L);
        int streakBonusEvery = (int) clamp(cfg.getLong("streak.bonus-every", 5L), 0L, 100L);

        Map<TreasureRank, Integer> rankRewards = new EnumMap<>(TreasureRank.class);
        for (TreasureRank rank : TreasureRank.values()) {
            if (rank == TreasureRank.NOVICE) {
                continue;
            }
            int diamonds = (int) clamp(
                    cfg.getLong("ranks." + rank.id() + ".diamonds", rank.diamonds()),
                    0L, 576L);
            rankRewards.put(rank, diamonds);
        }

        Effects effects = new Effects(
                cfg.getBoolean("effects.titles", true),
                cfg.getBoolean("effects.actionbar", true),
                cfg.getBoolean("effects.fireworks", true),
                parseTier(cfg.getString("effects.title-min-tier"), TreasureTier.EPIC),
                parseTier(cfg.getString("effects.firework-min-tier"), TreasureTier.LEGENDARY));

        return new TreasuresConfig(enabled, auraTicks, topSize,
                Collections.unmodifiableMap(chances),
                Collections.unmodifiableMap(itemChances),
                Collections.unmodifiableMap(blocks),
                Collections.unmodifiableMap(blockTables),
                depthPerBlock, depthMax, ttl, maxEntries,
                cooldown, Collections.unmodifiableMap(milestones),
                broadcastLegendary, broadcastMythical, broadcastLegend,
                streakWindow, streakBonusEvery,
                Collections.unmodifiableMap(rankRewards), effects,
                cfg.getBoolean("abilities.enabled", true),
                cfg.getBoolean("curses.enabled", true),
                cfg.getBoolean("sets.enabled", true),
                arsenalShare);
    }

    private static TreasureTier parseTier(String raw, TreasureTier fallback) {
        TreasureTier tier = TreasureTier.byId(raw);
        return tier == null ? fallback : tier;
    }

    /** Встроенный набор блоков-кладоносцев. */
    public static Map<String, Double> defaultBlocks() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String stone : new String[]{"STONE", "COBBLESTONE", "DEEPSLATE", "TUFF",
                "GRANITE", "DIORITE", "ANDESITE", "CALCITE", "SMOOTH_BASALT"}) {
            out.put(stone, 1.0);
        }
        out.put("COBBLED_DEEPSLATE", 1.1);
        out.put("NETHERRACK", 1.1);
        out.put("BLACKSTONE", 1.2);
        out.put("BASALT", 1.1);
        out.put("END_STONE", 1.2);
        out.put("OBSIDIAN", 1.5);
        out.put("CRYING_OBSIDIAN", 1.5);
        for (String ore : new String[]{"COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE",
                "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "GOLD_ORE",
                "DEEPSLATE_GOLD_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "LAPIS_ORE",
                "DEEPSLATE_LAPIS_ORE", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE",
                "DEEPSLATE_EMERALD_ORE", "NETHER_GOLD_ORE", "NETHER_QUARTZ_ORE"}) {
            out.put(ore, 2.0);
        }
        out.put("ANCIENT_DEBRIS", 4.0);
        return Collections.unmodifiableMap(out);
    }

    static Map<Integer, Milestone> defaultMilestones() {
        Map<Integer, Milestone> out = new LinkedHashMap<>();
        out.put(10, new Milestone(10, 8));
        out.put(25, new Milestone(25, 24));
        out.put(40, new Milestone(40, 48));
        out.put(TreasureCatalog.EXPECTED_ITEMS,
                new Milestone(TreasureCatalog.EXPECTED_ITEMS, 96));
        out.put(80, new Milestone(80, 128));
        out.put(TreasureRegistry.size(), new Milestone(TreasureRegistry.size(), 192));
        return out;
    }

    public boolean enabled() {
        return enabled;
    }

    public int auraRefreshTicks() {
        return auraRefreshTicks;
    }

    public int topSize() {
        return topSize;
    }

    public double chance(TreasureTier tier) {
        return chances.getOrDefault(tier, 0.0);
    }

    public double itemChance(TreasureTier tier) {
        return itemChances.getOrDefault(tier, 0.0);
    }

    /** Шансы всех редкостей одной картой — чтобы не собирать её на каждый блок. */
    public Map<TreasureTier, Double> chances() {
        return chances;
    }

    /** Множитель блока; 0 — блок не может содержать клад. */
    public double blockMultiplier(String material) {
        if (material == null) {
            return 0.0;
        }
        return blocks.getOrDefault(material.toUpperCase(java.util.Locale.ROOT), 0.0);
    }

    /**
     * Точные проценты на блок для всех редкостей или {@code null}, если у блока
     * задан только множитель. Карта неизменяемая — её можно кэшировать.
     */
    public Map<TreasureTier, Double> blockChances(String material) {
        if (material == null) {
            return null;
        }
        return blockTables.get(material.toUpperCase(java.util.Locale.ROOT));
    }

    /** Блок может дать клад: задан либо множитель, либо таблица процентов. */
    public boolean mineable(String material) {
        return blockMultiplier(material) > 0.0 || blockChances(material) != null;
    }

    /** Множители блоков старого формата. */
    public Map<String, Double> blocks() {
        return blocks;
    }

    /** Всего блоков-кладоносцев: множители и таблицы вместе. */
    public int blockCount() {
        return blocks.size() + blockTables.size();
    }

    public double depthPerBlock() {
        return depthPerBlock;
    }

    public double depthMaxBonus() {
        return depthMaxBonus;
    }

    public long placedBlockTtlSeconds() {
        return placedBlockTtlSeconds;
    }

    public int placedBlockMaxEntries() {
        return placedBlockMaxEntries;
    }

    public long cooldownMillis() {
        return cooldownMillis;
    }

    public Map<Integer, Milestone> milestones() {
        return milestones;
    }

    public boolean broadcastLegendary() {
        return broadcastLegendary;
    }

    public boolean broadcastMythical() {
        return broadcastMythical;
    }

    /** Объявлять всему серверу достижение ранга «Легенда». */
    public boolean broadcastLegend() {
        return broadcastLegend;
    }

    /** Окно серии в миллисекундах; 0 — серии выключены (каждая находка новая). */
    public long streakWindowMillis() {
        return streakWindowSeconds * 1000L;
    }

    public int streakWindowSeconds() {
        return streakWindowSeconds;
    }

    /** Каждая N-я находка серии даёт бонус; 0 — бонусов нет. */
    public int streakBonusEvery() {
        return streakBonusEvery;
    }

    public int rankReward(TreasureRank rank) {
        if (rank == null || rank == TreasureRank.NOVICE) {
            return 0;
        }
        return rankRewards.getOrDefault(rank, rank.diamonds());
    }

    public Map<TreasureRank, Integer> rankRewards() {
        return rankRewards;
    }

    public Effects effects() {
        return effects;
    }

    /** Работают ли активные способности находок по ПКМ. */
    public boolean abilitiesEnabled() {
        return abilitiesEnabled;
    }

    /** Срабатывают ли проклятия легендарных и мифических находок. */
    public boolean cursesEnabled() {
        return cursesEnabled;
    }

    /** Доля арсенала Mine Treasure среди именных находок 0..1. */
    public double arsenalShare() {
        return arsenalShare;
    }

    /** Действуют ли сетовые бонусы за 2 и 4 предмета региона. */
    public boolean setsEnabled() {
        return setsEnabled;
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
