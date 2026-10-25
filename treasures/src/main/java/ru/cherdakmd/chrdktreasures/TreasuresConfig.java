package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Клад-карты (1.4.153): как часто они выпадают, сколько копать и где.
     *
     * <p>Все числа клампятся здесь, а не в момент использования: админ,
     * поставивший {@code radius: 5000}, должен увидеть в логе границу, а не
     * получить зону на полмира. Нулевой шанс выпадения — законный способ
     * оставить карты только за награды и админскую выдачу.</p>
     *
     * @param enabled               включены ли карты вообще
     * @param dropChancePercent     шанс, что к найденному кладу приложится карта
     * @param maxActive             сколько карт может быть у одного игрока
     * @param digsMin               минимум блоков для раскопок
     * @param digsMax               максимум блоков для раскопок
     * @param radius                радиус зоны поиска, блоки
     * @param expireMinutes         срок жизни карты в минутах; 0 — бессрочно
     * @param namedChanceMultiplier множитель шанса именной находки в тайнике
     * @param distanceMin           минимальное расстояние от игрока до тайника
     * @param distanceMax           максимальное расстояние от игрока до тайника
     * @param hintTicks             период подсказки в экшен-баре, тики
     * @param hintDistance          дальше этого расстояния подсказку не показывать (0 — всегда)
     * @param tierWeights           веса редкостей тайника (нормализуются)
     */
    public record Maps(boolean enabled, double dropChancePercent, int maxActive,
                       int digsMin, int digsMax, int radius, int expireMinutes,
                       double namedChanceMultiplier, int distanceMin, int distanceMax,
                       int hintTicks, int hintDistance,
                       Map<TreasureTier, Double> tierWeights) {

        /** Карты выключены: сервис не создаёт и не выдаёт ни одной. */
        public static final Maps DISABLED = new Maps(false, 0.0, 3, 8, 20, 24, 0,
                1.5, 120, 480, 40, 256, defaultTierWeights());

        public Maps {
            tierWeights = tierWeights == null || tierWeights.isEmpty()
                    ? defaultTierWeights()
                    : Collections.unmodifiableMap(new EnumMap<>(tierWeights));
            radius = Math.clamp(radius, TreasureMap.MIN_RADIUS, TreasureMap.MAX_RADIUS);
            digsMin = Math.clamp(digsMin, 3, TreasureMap.MAX_DIGS);
            digsMax = Math.clamp(digsMax, digsMin, TreasureMap.MAX_DIGS);
            maxActive = Math.clamp(maxActive, 1, 9);
            expireMinutes = Math.clamp(expireMinutes, 0, 1440);
            distanceMin = Math.clamp(distanceMin, 32, 2000);
            distanceMax = Math.clamp(distanceMax, distanceMin, 4000);
            hintTicks = Math.clamp(hintTicks, 20, 200);
            hintDistance = Math.clamp(hintDistance, 0, 1024);
            dropChancePercent = Math.clamp(dropChancePercent, 0.0, 100.0);
            namedChanceMultiplier = Math.clamp(namedChanceMultiplier, 0.5, 3.0);
        }

        /** Шанс выпадения как доля 0..1. */
        public double dropChance() {
            return Math.clamp(dropChancePercent, 0.0, 100.0) / 100.0;
        }

        /** Срок жизни в миллисекундах; 0 — карта не сгорает. */
        public long expireMillis() {
            return expireMinutes * 60_000L;
        }

        /** Веса по умолчанию: обычный тайник — самый частый. */
        public static Map<TreasureTier, Double> defaultTierWeights() {
            Map<TreasureTier, Double> out = new EnumMap<>(TreasureTier.class);
            out.put(TreasureTier.COMMON, 45.0);
            out.put(TreasureTier.RARE, 34.0);
            out.put(TreasureTier.EPIC, 15.0);
            out.put(TreasureTier.LEGENDARY, 5.5);
            out.put(TreasureTier.MYTHICAL, 0.5);
            return Collections.unmodifiableMap(out);
        }

        /** Вес редкости по умолчанию — для разбора конфига. */
        public static double defaultWeight(TreasureTier tier) {
            Double weight = defaultTierWeights().get(tier);
            return weight == null ? 0.0 : weight;
        }
    }

    /**
     * Клады-бочки (1.4.166): найденный клад появляется бочкой на месте блока.
     *
     * <p>Бочка — настоящий контейнер: добыча лежит внутри, а не выдаётся в
     * инвентарь. Владелец открывает её сам, остальные видят замок (если включён
     * {@code owner-only}). Срок жизни и лимит на игрока защищают мир от сотен
     * бесхозных бочек: просроченная бочка исчезает с эффектом, а её содержимое
     * высыпается на землю, поэтому находка не пропадает.</p>
     *
     * @param enabled           включены ли бочки вместо прямой выдачи
     * @param ownerOnly         открывать может только нашедший (админ — всегда)
     * @param expireSeconds     срок жизни бочки в секундах; 0 — бессрочно
     * @param maxPerPlayer      сколько живых бочек может быть у одного игрока
     * @param animation         частицы, звуки и крышка при появлении и открытии
     * @param removeEmptyTicks  через сколько тиков убирать опустевшую бочку
     * @param dropOnExpire      высыпать содержимое при истечении срока
     */
    public record Barrels(boolean enabled, boolean ownerOnly, int expireSeconds,
                          int maxPerPlayer, boolean animation, int removeEmptyTicks,
                          boolean dropOnExpire, boolean overflowToOwner) {

        /** Бочки выключены: клад выдаётся в инвентарь, как раньше. */
        public static final Barrels DISABLED =
                new Barrels(false, true, 15, 3, true, 60, true, true);

        public Barrels {
            expireSeconds = clampInt(expireSeconds, 0, 3_600);
            maxPerPlayer = clampInt(maxPerPlayer, 1, 20);
            removeEmptyTicks = clampInt(removeEmptyTicks, 20, 600);
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        /** Срок жизни в миллисекундах; 0 — бочка не исчезает. */
        public long expireMillis() {
            return expireSeconds * 1_000L;
        }
    }

    /**
     * Особые находки (1.4.166): реликвии CHRDK_Artifacts и книги чар
     * CHRDK_Enchants прямо в кладе.
     *
     * <p>Оба источника — мягкие: аддона нет, мост не зарегистрирован или старая
     * версия без нужного метода — находка просто не выпадает, а остальной клад
     * выдаётся как обычно. Шансы заданы по редкости клада: обычный клад почти
     * никогда не приносит реликвию, мифический — приносит всегда.</p>
     *
     * @param enabled              главный выключатель особых находок
     * @param artifacts            выдавать ли реликвии
     * @param artifactChance       шанс реликвии по редкости, проценты
     * @param cursedArtifactPercent доля проклятых реликвий среди найденных
     * @param enchantments         выдавать ли книги чар
     * @param enchantChance        шанс книги по редкости, проценты
     * @param enchantLevelCap      потолок уровня книги по редкости
     * @param curseBookPercent     доля книг с проклятием среди найденных
     */
    public record Specials(boolean enabled, boolean artifacts,
                           Map<TreasureTier, Double> artifactChance,
                           int cursedArtifactPercent,
                           boolean enchantments,
                           Map<TreasureTier, Double> enchantChance,
                           Map<TreasureTier, Integer> enchantLevelCap,
                           int curseBookPercent) {

        /** Шансы реликвий по редкости: 1 / 3 / 8 / 20 / 100 %. */
        public static final double[] DEFAULT_ARTIFACT_CHANCE = {1.0, 3.0, 8.0, 20.0, 100.0};
        /** Шансы книг чар: 5 / 9 / 16 / 30 / 60 %. */
        public static final double[] DEFAULT_ENCHANT_CHANCE = {5.0, 9.0, 16.0, 30.0, 60.0};
        /** Потолки уровня книги: обычный клад даёт I, мифический — до V. */
        public static final int[] DEFAULT_ENCHANT_LEVEL_CAP = {1, 1, 2, 3, 5};

        public Specials {
            cursedArtifactPercent = clampInt(cursedArtifactPercent, 0, 100);
            curseBookPercent = clampInt(curseBookPercent, 0, 100);
            artifactChance = cleanChances(artifactChance, DEFAULT_ARTIFACT_CHANCE);
            enchantChance = cleanChances(enchantChance, DEFAULT_ENCHANT_CHANCE);
            enchantLevelCap = cleanLevels(enchantLevelCap);
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static Map<TreasureTier, Double> cleanChances(Map<TreasureTier, Double> raw,
                                                              double[] fallback) {
            Map<TreasureTier, Double> out = new EnumMap<>(TreasureTier.class);
            for (TreasureTier tier : TreasureTier.values()) {
                Double value = raw == null ? null : raw.get(tier);
                double base = value == null ? fallback[tier.ordinal()] : value;
                out.put(tier, Math.max(0.0, Math.min(100.0, base)));
            }
            return Collections.unmodifiableMap(out);
        }

        private static Map<TreasureTier, Integer> cleanLevels(Map<TreasureTier, Integer> raw) {
            Map<TreasureTier, Integer> out = new EnumMap<>(TreasureTier.class);
            for (TreasureTier tier : TreasureTier.values()) {
                Integer value = raw == null ? null : raw.get(tier);
                int base = value == null ? DEFAULT_ENCHANT_LEVEL_CAP[tier.ordinal()] : value;
                out.put(tier, Math.max(1, Math.min(5, base)));
            }
            return Collections.unmodifiableMap(out);
        }

        /** Шанс реликвии для редкости клада, проценты. */
        public double artifactChance(TreasureTier tier) {
            Double value = artifactChance.get(tier);
            return value == null ? 0.0 : value;
        }

        /** Шанс книги чар для редкости клада, проценты. */
        public double enchantChance(TreasureTier tier) {
            Double value = enchantChance.get(tier);
            return value == null ? 0.0 : value;
        }

        /** Потолок уровня книги для редкости клада. */
        public int enchantLevelCap(TreasureTier tier) {
            Integer value = enchantLevelCap.get(tier);
            return value == null ? 1 : value;
        }

        /** Включены ли особые находки хоть одним источником. */
        public boolean active() {
            return enabled && (artifacts || enchantments);
        }
    }

    /**
     * Осколки кладов (1.4.166): валюта находок и обменник за них.
     *
     * <p>Осколки — счётчик в профиле, а не предмет: их не теряют при смерти и
     * не носят в рюкзаке. За клад каждой редкости выпадает свой диапазон, шанс
     * задаётся отдельно — обычный клад иногда даёт один-два, мифический всегда
     * приносит горсть. Тратятся они в обменнике ({@code shards.shop}): именная
     * находка нужной редкости, книга чар, реликвия или обычный предмет.</p>
     *
     * @param enabled включены ли осколки и обменник
     * @param ranges  диапазон и шанс по редкости клада
     * @param offers  предложения обменника
     */
    public record Shards(boolean enabled, Map<TreasureTier, Range> ranges,
                         List<TreasureShardShop.Offer> offers) {

        /**
         * Диапазон осколков одной редкости.
         *
         * @param minAmount     минимум осколков
         * @param maxAmount     максимум осколков
         * @param chancePercent шанс, что осколки вообще выпадут
         */
        public record Range(int minAmount, int maxAmount, double chancePercent) {

            public Range {
                minAmount = Math.max(0, Math.min(64, minAmount));
                maxAmount = Math.max(minAmount, Math.min(64, maxAmount));
                chancePercent = Math.max(0.0, Math.min(100.0, chancePercent));
            }

            /** Разбирается ли диапазон: иначе клад осколков не даёт. */
            public boolean active() {
                return maxAmount > 0 && chancePercent > 0.0;
            }
        }

        /** Диапазоны по умолчанию: обычный 1–2 (55 %), мифический 12–18 (100 %). */
        public static final int[][] DEFAULT_RANGES = {
                {1, 2}, {2, 4}, {4, 7}, {7, 11}, {12, 18}};
        /** Шансы по умолчанию, проценты. */
        public static final double[] DEFAULT_CHANCE = {55.0, 75.0, 100.0, 100.0, 100.0};

        public Shards {
            Map<TreasureTier, Range> clean = new EnumMap<>(TreasureTier.class);
            for (TreasureTier tier : TreasureTier.values()) {
                Range range = ranges == null ? null : ranges.get(tier);
                if (range == null) {
                    int[] pair = DEFAULT_RANGES[tier.ordinal()];
                    range = new Range(pair[0], pair[1], DEFAULT_CHANCE[tier.ordinal()]);
                }
                clean.put(tier, range);
            }
            ranges = Collections.unmodifiableMap(clean);
            offers = offers == null ? List.of() : List.copyOf(offers);
        }

        /** Диапазон осколков редкости. */
        public Range range(TreasureTier tier) {
            Range range = ranges.get(tier);
            return range == null ? new Range(0, 0, 0.0) : range;
        }

        /** Есть ли что покупать. */
        public boolean shopReady() {
            return enabled && !offers.isEmpty();
        }
    }

    /**
     * Звание в имени (1.4.166): ранг кладоискателя рядом с ником в чате и табе.
     *
     * <p>Звание выдаётся за находки и видно всем — это не покупка и не титул,
     * который можно «надеть» без заслуги. Игрок может скрыть его у себя
     * ({@code /treasures badge off}) или вернуть ({@code auto} — как в конфиге,
     * {@code on} — показывать). Ниже {@code min-rank} звание не показывается:
     * «Новичок» в имени был бы шумом, а не гордостью.</p>
     *
     * @param enabled        включено ли звание в имени
     * @param defaultShown   показывать ли звание тем, кто ещё не выбирал
     * @param minRank        с какого звания начинать показывать
     * @param tab            рисовать ли имя в таб-листе (если ядро его не ведёт)
     * @param tabRefreshTicks период обновления имён в табе (20..1200)
     */
    public record RankTag(boolean enabled, boolean defaultShown, TreasureRank minRank,
                          boolean tab, int tabRefreshTicks) {

        public RankTag {
            minRank = minRank == null ? TreasureRank.SEEKER : minRank;
            tabRefreshTicks = (int) Math.max(20L, Math.min(1200L, tabRefreshTicks));
        }

        /** Показывать ли звание конкретному игроку ({@code choice == null} — «авто»). */
        public boolean shows(TreasureRank rank, TreasureRankTags.Choice choice) {
            return TreasureRankTags.shows(enabled, defaultShown, minRank, rank, choice);
        }
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
    /** 1.4.148: писать в чат, что именно выпало из клада. */
    private final boolean showLoot;
    /** Сколько позиций добычи перечислять в строке (1..12). */
    private final int lootDetailsMax;
    /** Дублировать каждую находку в консоль сервера с составом. */
    private final boolean logFinds;
    private final int streakWindowSeconds;
    private final int streakBonusEvery;
    private final Map<TreasureRank, Integer> rankRewards;
    private final Effects effects;
    private final boolean abilitiesEnabled;
    private final boolean cursesEnabled;
    private final boolean setsEnabled;
    private final double arsenalShare;
    /** 1.4.141: шанс тира CHRDK_TierForge по редкости находки, проценты. */
    private final boolean tiersEnabled;
    private final Map<TreasureTier, Double> tierChances;
    /** 1.4.153: клад-карты — шанс выпадения, зона и срок жизни. */
    private final Maps maps;
    private final Barrels barrels;
    /** 1.4.166: реликвии и книги чар из соседних аддонов. */
    private final Specials specials;
    /** 1.4.166: валюта кладов и обменник. */
    private final Shards shards;
    /** 1.4.166: звание кладоискателя рядом с ником в чате и табе. */
    private final RankTag rankTag;
    /** 1.4.157: склянки опыта в каждом кладе. */
    private final TreasureExperience.Rule experience;

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
                            boolean broadcastLegend, boolean showLoot, int lootDetailsMax,
                            boolean logFinds, int streakWindowSeconds, int streakBonusEvery,
                            Map<TreasureRank, Integer> rankRewards, Effects effects,
                            boolean abilitiesEnabled, boolean cursesEnabled,
                            boolean setsEnabled, double arsenalShare,
                            boolean tiersEnabled, Map<TreasureTier, Double> tierChances,
                            Maps maps, Barrels barrels, Specials specials, Shards shards,
                            RankTag rankTag, TreasureExperience.Rule experience) {
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
        this.showLoot = showLoot;
        this.lootDetailsMax = lootDetailsMax;
        this.logFinds = logFinds;
        this.streakWindowSeconds = streakWindowSeconds;
        this.streakBonusEvery = streakBonusEvery;
        this.rankRewards = rankRewards;
        this.effects = effects;
        this.abilitiesEnabled = abilitiesEnabled;
        this.cursesEnabled = cursesEnabled;
        this.setsEnabled = setsEnabled;
        this.arsenalShare = arsenalShare;
        this.tiersEnabled = tiersEnabled;
        this.tierChances = tierChances;
        this.maps = maps == null ? Maps.DISABLED : maps;
        this.barrels = barrels == null ? Barrels.DISABLED : barrels;
        this.specials = specials == null ? defaultSpecials() : specials;
        this.shards = shards == null ? defaultShards() : shards;
        this.rankTag = rankTag == null ? defaultRankTag() : rankTag;
        this.experience = experience == null ? TreasureExperience.defaults() : experience;
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

        // 1.4.141: тир у находки. Шанс зависит от редкости самой находки:
        // обычная 20 %, мифическая 100 %. 0 выключает тир для этой редкости,
        // tiers.enabled: false — для всех сразу.
        boolean tiersEnabled = cfg.getBoolean("tiers.enabled", true);
        Map<TreasureTier, Double> tierChances = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            tierChances.put(tier, clamp(cfg.getDouble("tiers.chance-percent." + tier.id(),
                    tier.defaultTierChance()), 0.0, 100.0));
        }

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
        // 1.4.148: список добычи в чате. Клампим 1..12 — длиннее строка не нужна,
        // короче одной позиции список потерял бы смысл.
        boolean showLoot = cfg.getBoolean("feedback.show-loot", true);
        int lootDetailsMax = (int) clamp(
                cfg.getLong("feedback.loot-details-max", TreasureLootText.DEFAULT_LIMIT),
                1L, TreasureLootText.MAX_LIMIT);
        boolean logFinds = cfg.getBoolean("feedback.log-finds", true);

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
                showLoot, lootDetailsMax, logFinds,
                streakWindow, streakBonusEvery,
                Collections.unmodifiableMap(rankRewards), effects,
                cfg.getBoolean("abilities.enabled", true),
                cfg.getBoolean("curses.enabled", true),
                cfg.getBoolean("sets.enabled", true),
                arsenalShare, tiersEnabled,
                Collections.unmodifiableMap(tierChances),
                parseMaps(cfg), parseBarrels(cfg), parseSpecials(cfg),
                parseShards(cfg, logger), parseRankTag(cfg), parseExperience(cfg));
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
        // 1.4.141: коллекция выросла вдвое, вехи сдвинуты вместе с ней.
        out.put(20, new Milestone(20, 8));
        out.put(50, new Milestone(50, 24));
        out.put(80, new Milestone(80, 48));
        out.put(TreasureCatalog.EXPECTED_ITEMS,
                new Milestone(TreasureCatalog.EXPECTED_ITEMS, 96));
        out.put(160, new Milestone(160, 128));
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

    /** Включены ли тиры CHRDK_TierForge у находок. */
    public boolean tiersEnabled() {
        return tiersEnabled;
    }

    /** Шанс в процентах, что находка этой редкости получит тир. */
    public double tierChance(TreasureTier tier) {
        return tierChances.getOrDefault(tier, 0.0);
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

    /** Перечислять в чате, что именно выпало из клада. */
    public boolean showLoot() {
        return showLoot;
    }

    /** Сколько позиций добычи показывать в одной строке (1..12). */
    public int lootDetailsMax() {
        return lootDetailsMax;
    }

    /** Писать каждую находку с составом в консоль сервера. */
    public boolean logFinds() {
        return logFinds;
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

    /** Настройки клад-карт (1.4.153). */
    public Maps maps() {
        return maps;
    }

    /** Клады-бочки: появление, срок жизни и анимация. */
    public Barrels barrels() {
        return barrels;
    }

    /** Реликвии и книги чар в кладе (1.4.166). */
    public Specials specials() {
        return specials;
    }

    /** Осколки кладов и обменник за них (1.4.166). */
    public Shards shards() {
        return shards;
    }

    /** Звание кладоискателя в имени: чат и таб-лист (1.4.166). */
    public RankTag rankTag() {
        return rankTag;
    }

    /** Склянки опыта в каждом кладе (1.4.157). */
    public TreasureExperience.Rule experience() {
        return experience;
    }

    /**
     * Разбор секции {@code experience.*}: диапазоны по редкостям, шанс бонуса
     * и потолок. Всё, что вне границ, зажимается в
     * {@link TreasureExperience.Rule}, поэтому здесь только чтение.
     */
    private static TreasureExperience.Rule parseExperience(FileConfiguration cfg) {
        TreasureExperience.Rule defaults = TreasureExperience.defaults();
        Map<TreasureTier, TreasureExperience.Range> ranges =
                new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            TreasureExperience.Range fallback = defaults.range(tier);
            int min = (int) clamp(cfg.getLong("experience.bottles." + tier.id() + ".min",
                    fallback.min()), 0L, TreasureExperience.MAX_PER_TIER);
            int max = (int) clamp(cfg.getLong("experience.bottles." + tier.id() + ".max",
                    fallback.max()), 0L, TreasureExperience.MAX_PER_TIER);
            ranges.put(tier, new TreasureExperience.Range(Math.min(min, max),
                    Math.max(min, max)));
        }
        return new TreasureExperience.Rule(
                cfg.getBoolean("experience.enabled", true),
                ranges,
                (int) clamp(cfg.getLong("experience.bonus-chance-percent", 25L), 0L, 100L),
                (int) clamp(cfg.getLong("experience.bonus-multiplier-percent", 100L), 0L, 400L),
                (int) clamp(cfg.getLong("experience.max-per-treasure", 32L), 1L,
                        TreasureExperience.MAX_PER_TREASURE));
    }

    /**
     * Разбор секции {@code maps.*}. Значения вне диапазона зажимаются в
     * конструкторе {@link Maps}, поэтому здесь только чтение с умолчаниями.
     */
    private static Maps parseMaps(FileConfiguration cfg) {
        Map<TreasureTier, Double> weights = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            weights.put(tier, clamp(cfg.getDouble("maps.tier-chance-percent." + tier.id(),
                    Maps.defaultWeight(tier)), 0.0, 100.0));
        }
        return new Maps(
                cfg.getBoolean("maps.enabled", true),
                clamp(cfg.getDouble("maps.drop-chance-percent", 12.0), 0.0, 100.0),
                (int) clamp(cfg.getLong("maps.max-active", 3L), 1L, 9L),
                (int) clamp(cfg.getLong("maps.digs-min", 8L), 3L, TreasureMap.MAX_DIGS),
                (int) clamp(cfg.getLong("maps.digs-max", 20L), 3L, TreasureMap.MAX_DIGS),
                (int) clamp(cfg.getLong("maps.radius", 24L),
                        TreasureMap.MIN_RADIUS, TreasureMap.MAX_RADIUS),
                (int) clamp(cfg.getLong("maps.expire-minutes", 240L), 0L, 1440L),
                clamp(cfg.getDouble("maps.named-chance-multiplier", 1.5), 0.5, 3.0),
                (int) clamp(cfg.getLong("maps.distance-min", 120L), 32L, 2000L),
                (int) clamp(cfg.getLong("maps.distance-max", 480L), 32L, 4000L),
                (int) clamp(cfg.getLong("maps.hint-ticks", 40L), 20L, 200L),
                (int) clamp(cfg.getLong("maps.hint-distance", 256L), 0L, 1024L),
                weights);
    }

    /**
     * Разбор секции {@code barrels.*}. Границы те же, что в конструкторе
     * {@link Barrels}: опечатка в конфиге не может оставить бочку навсегда или
     * разрешить сотни бочек одному игроку.
     */
    private static Barrels parseBarrels(FileConfiguration cfg) {
        return new Barrels(
                cfg.getBoolean("barrels.enabled", true),
                cfg.getBoolean("barrels.owner-only", true),
                (int) clamp(cfg.getLong("barrels.expire-seconds", 15L), 0L, 3_600L),
                (int) clamp(cfg.getLong("barrels.max-per-player", 3L), 1L, 20L),
                cfg.getBoolean("barrels.animation", true),
                (int) clamp(cfg.getLong("barrels.remove-empty-ticks", 60L), 20L, 600L),
                cfg.getBoolean("barrels.drop-on-expire", true),
                cfg.getBoolean("barrels.overflow-to-owner", true));
    }

    /** Особые находки по умолчанию: те же значения, что в поставляемом config.yml. */
    public static Specials defaultSpecials() {
        return parseSpecials(new YamlConfiguration());
    }

    /**
     * Разбор секции {@code specials.*}. Шансы читаются по редкостям, потолки
     * уровня книги тоже; всё остальное зажимается в {@link Specials}.
     */
    private static Specials parseSpecials(FileConfiguration cfg) {
        Map<TreasureTier, Double> artifact = new EnumMap<>(TreasureTier.class);
        Map<TreasureTier, Double> enchant = new EnumMap<>(TreasureTier.class);
        Map<TreasureTier, Integer> caps = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            int index = tier.ordinal();
            artifact.put(tier, clamp(cfg.getDouble(
                    "specials.artifacts.chance-percent." + tier.id(),
                    Specials.DEFAULT_ARTIFACT_CHANCE[index]), 0.0, 100.0));
            enchant.put(tier, clamp(cfg.getDouble(
                    "specials.enchantments.chance-percent." + tier.id(),
                    Specials.DEFAULT_ENCHANT_CHANCE[index]), 0.0, 100.0));
            caps.put(tier, (int) clamp(cfg.getLong(
                    "specials.enchantments.max-level." + tier.id(),
                    Specials.DEFAULT_ENCHANT_LEVEL_CAP[index]), 1L, 5L));
        }
        return new Specials(
                cfg.getBoolean("specials.enabled", true),
                cfg.getBoolean("specials.artifacts.enabled", true),
                artifact,
                (int) clamp(cfg.getLong("specials.artifacts.cursed-chance-percent", 35L),
                        0L, 100L),
                cfg.getBoolean("specials.enchantments.enabled", true),
                enchant,
                caps,
                (int) clamp(cfg.getLong("specials.enchantments.curse-chance-percent", 10L),
                        0L, 100L));
    }

    /** Звание в имени по умолчанию: показывать с «Искателя». */
    public static RankTag defaultRankTag() {
        return parseRankTag(new YamlConfiguration());
    }

    /**
     * Разбор секции {@code rank-tag.*}.
     *
     * <p>Неизвестный {@code min-rank} не выключает показ целиком: берётся
     * «Искатель» — консервативный вариант, при котором новичок не получает
     * звание в имя, но и опытный игрок не теряет его из-за опечатки.</p>
     */
    private static RankTag parseRankTag(FileConfiguration cfg) {
        TreasureRank min = TreasureRank.byId(cfg.getString("rank-tag.min-rank", "seeker"));
        return new RankTag(
                cfg.getBoolean("rank-tag.enabled", true),
                cfg.getBoolean("rank-tag.default", true),
                min == null ? TreasureRank.SEEKER : min,
                cfg.getBoolean("rank-tag.tab.enabled", true),
                (int) clamp(cfg.getLong("rank-tag.tab.refresh-ticks", 60L), 20L, 1200L));
    }

    /** Осколки по умолчанию: те же значения, что в поставляемом config.yml. */
    public static Shards defaultShards() {
        return parseShards(new YamlConfiguration(), null);
    }

    /**
     * Разбор секции {@code shards.*}: диапазоны по редкостям и каталог обменника.
     * Битое предложение пропускается с предупреждением — из-за одной опечатки
     * обменник не должен исчезать целиком.
     */
    private static Shards parseShards(FileConfiguration cfg, Logger logger) {
        Map<TreasureTier, Shards.Range> ranges = new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            int index = tier.ordinal();
            int[] pair = Shards.DEFAULT_RANGES[index];
            int min = (int) clamp(cfg.getLong("shards.amount." + tier.id() + ".min",
                    pair[0]), 0L, 64L);
            int max = (int) clamp(cfg.getLong("shards.amount." + tier.id() + ".max",
                    pair[1]), 0L, 64L);
            double chance = clamp(cfg.getDouble(
                    "shards.amount." + tier.id() + ".chance-percent",
                    Shards.DEFAULT_CHANCE[index]), 0.0, 100.0);
            ranges.put(tier, new Shards.Range(Math.min(min, max), Math.max(min, max), chance));
        }
        // Списка нет вовсе — работаем со встроенным обменником; список есть, но
        // битый — обменник пуст, и в журнале уже есть предупреждения: тихо
        // подменять админский каталог умолчаниями нельзя.
        ConfigurationSection section = cfg.getConfigurationSection("shards");
        List<TreasureShardShop.Offer> offers = cfg.isSet("shards.shop") && section != null
                ? TreasureShardShop.parse(section, logger)
                : defaultOffers();
        return new Shards(cfg.getBoolean("shards.enabled", true), ranges, offers);
    }

    /** Встроенный обменник: работает, даже если секции {@code shards.shop} нет. */
    public static List<TreasureShardShop.Offer> defaultOffers() {
        return List.of(
                new TreasureShardShop.Offer("named-common", TreasureShardShop.Kind.NAMED,
                        15, TreasureTier.COMMON, 1, "", 1, ""),
                new TreasureShardShop.Offer("named-rare", TreasureShardShop.Kind.NAMED,
                        45, TreasureTier.RARE, 1, "", 1, ""),
                new TreasureShardShop.Offer("named-epic", TreasureShardShop.Kind.NAMED,
                        140, TreasureTier.EPIC, 1, "", 1, ""),
                new TreasureShardShop.Offer("book-1", TreasureShardShop.Kind.ENCHANT,
                        60, TreasureTier.COMMON, 1, "", 1, ""),
                new TreasureShardShop.Offer("book-3", TreasureShardShop.Kind.ENCHANT,
                        200, TreasureTier.EPIC, 3, "", 1, ""),
                new TreasureShardShop.Offer("artifact", TreasureShardShop.Kind.ARTIFACT,
                        260, TreasureTier.RARE, 1, "", 1, ""),
                new TreasureShardShop.Offer("bottles", TreasureShardShop.Kind.ITEM,
                        10, TreasureTier.COMMON, 1, "EXPERIENCE_BOTTLE", 8, ""));
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
