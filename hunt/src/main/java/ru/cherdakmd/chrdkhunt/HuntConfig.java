package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} охоты (1.4.155).
 *
 * <p>Все числа клампятся здесь: админ, поставивший {@code coins-per-day: 999999999},
 * увидит в логе предупреждение и получит потолок, а не бесконечную экономику.
 * Материалы и типы мобов проверяются при разборе: опечатка пропускается с
 * предупреждением, а не роняет плагин и не висит мёртвым правилом.</p>
 */
public final class HuntConfig {

    /** Сколько строк показывает {@code /hunt top}. */
    public static final int MAX_TOP_SIZE = 50;
    /** Сколько позиций влезает в меню лавки и трофеев. */
    public static final int MAX_MENU_ITEMS = 21;
    /** Сколько семейств максимум помещается на страницу ключей. */
    public static final int MAX_FAMILIES = 14;

    private final boolean enabled;
    private final String locale;
    private final int topSize;
    private final boolean actionbar;
    private final Set<String> disabledWorlds;

    private final int coinsPerDay;
    private final int exchangePerDay;
    private final int coinsPerDiamond;
    private final int exchangeMin;

    private final boolean blockSpawners;
    private final boolean blockEggs;
    private final boolean blockCommand;

    private final Map<String, HuntFamily> families;
    private final List<MobRule> mobRules;
    private final Map<EntityType, MobRule> rulesByType;
    private final List<HuntShopOffer> shop;
    private final Map<String, HuntShopOffer> shopById;
    private final List<HuntTrophy> trophies;
    private final Map<String, HuntTrophy> trophyById;
    private final HuntBestiary bestiary;
    private final TreeMap<Integer, String> ranks;

    private HuntConfig(boolean enabled, String locale, int topSize, boolean actionbar,
                       Set<String> disabledWorlds, int coinsPerDay, int exchangePerDay,
                       int coinsPerDiamond, int exchangeMin, boolean blockSpawners,
                       boolean blockEggs, boolean blockCommand, Map<String, HuntFamily> families,
                       List<MobRule> mobRules, List<HuntShopOffer> shop, List<HuntTrophy> trophies,
                       HuntBestiary bestiary, TreeMap<Integer, String> ranks) {
        this.enabled = enabled;
        this.locale = locale;
        this.topSize = topSize;
        this.actionbar = actionbar;
        this.disabledWorlds = Set.copyOf(disabledWorlds);
        this.coinsPerDay = coinsPerDay;
        this.exchangePerDay = exchangePerDay;
        this.coinsPerDiamond = coinsPerDiamond;
        this.exchangeMin = exchangeMin;
        this.blockSpawners = blockSpawners;
        this.blockEggs = blockEggs;
        this.blockCommand = blockCommand;
        this.families = Collections.unmodifiableMap(new LinkedHashMap<>(families));
        this.mobRules = List.copyOf(mobRules);
        Map<EntityType, MobRule> byType = new LinkedHashMap<>();
        for (MobRule rule : mobRules) {
            byType.putIfAbsent(rule.entityType(), rule);
        }
        this.rulesByType = Collections.unmodifiableMap(byType);
        this.shop = List.copyOf(shop);
        Map<String, HuntShopOffer> offers = new LinkedHashMap<>();
        for (HuntShopOffer offer : shop) {
            offers.putIfAbsent(offer.id(), offer);
        }
        this.shopById = Collections.unmodifiableMap(offers);
        this.trophies = List.copyOf(trophies);
        Map<String, HuntTrophy> trophyMap = new LinkedHashMap<>();
        for (HuntTrophy trophy : trophies) {
            trophyMap.putIfAbsent(trophy.id(), trophy);
        }
        this.trophyById = Collections.unmodifiableMap(trophyMap);
        this.bestiary = bestiary == null ? HuntBestiary.empty() : bestiary;
        this.ranks = new TreeMap<>(ranks);
    }

    /**
     * Разбирает конфиг; {@code null} допустим — тогда действуют умолчания.
     */
    public static HuntConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new org.bukkit.configuration.file.YamlConfiguration() : config;

        boolean enabled = source.getBoolean("general.enabled", true);
        String locale = HuntFamily.blankTo(source.getString("general.locale", "ru"), "ru");
        int topSize = (int) clamp(source.getLong("general.top-size", 10L), 3L, MAX_TOP_SIZE);
        warnClamped(logger, "general.top-size", 10L, topSize);
        boolean actionbar = source.getBoolean("general.actionbar", true);
        Set<String> disabledWorlds = new LinkedHashSet<>();
        for (String world : source.getStringList("general.disabled-worlds")) {
            if (world != null && !world.isBlank()) {
                disabledWorlds.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }

        int coinsPerDay = (int) clamp(source.getLong("limits.coins-per-day", 2_000L),
                0L, 1_000_000L);
        warnClamped(logger, "limits.coins-per-day", 2_000L, coinsPerDay);
        int exchangePerDay = (int) clamp(source.getLong("limits.exchange-per-day", 8L),
                0L, 100L);
        warnClamped(logger, "limits.exchange-per-day", 8L, exchangePerDay);
        int coinsPerDiamond = (int) clamp(source.getLong("limits.coins-per-diamond", 40L),
                1L, 100_000L);
        warnClamped(logger, "limits.coins-per-diamond", 40L, coinsPerDiamond);
        int exchangeMin = (int) clamp(source.getLong("limits.exchange-min", 1L), 1L, 100L);
        if (exchangePerDay > 0 && exchangeMin > exchangePerDay) {
            if (logger != null) {
                logger.warning("[CHRDK_Hunt] limits.exchange-min больше exchange-per-day ("
                        + exchangeMin + " > " + exchangePerDay + ") — обмен никогда не сработает.");
            }
            exchangeMin = exchangePerDay;
        }

        boolean blockSpawners = source.getBoolean("anti-farm.block-spawners", true);
        boolean blockEggs = source.getBoolean("anti-farm.block-eggs", true);
        boolean blockCommand = source.getBoolean("anti-farm.block-command", true);

        Map<String, HuntFamily> families = parseFamilies(source, logger);
        List<MobRule> mobs = parseMobs(source, families, logger);
        List<HuntShopOffer> shop = parseShop(source, logger);
        List<HuntTrophy> trophies = parseTrophies(source, families, logger);
        HuntBestiary bestiary = parseBestiary(source, families, mobs, logger);
        TreeMap<Integer, String> ranks = parseRanks(source, logger);

        if (enabled && (families.isEmpty() || mobs.isEmpty())) {
            if (logger != null) {
                logger.warning("[CHRDK_Hunt] В config.yml нет рабочих семейств или мобов — "
                        + "охота не будет давать добычу. Проверьте секции families и mobs.");
            }
        }
        if (enabled && families.size() > MAX_FAMILIES) {
            if (logger != null) {
                logger.warning("[CHRDK_Hunt] Семейств больше " + MAX_FAMILIES
                        + " — часть не поместится в меню ключей.");
            }
        }
        return new HuntConfig(enabled, locale, topSize, actionbar, disabledWorlds, coinsPerDay,
                exchangePerDay, coinsPerDiamond, exchangeMin, blockSpawners, blockEggs,
                blockCommand, families, mobs, shop, trophies, bestiary, ranks);
    }

    /* -------------------------------- семейства -------------------------------- */

    private static Map<String, HuntFamily> parseFamilies(FileConfiguration config, Logger logger) {
        Map<String, HuntFamily> out = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("families");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            String id = HuntFamily.normalize(key);
            if (id.isEmpty()) {
                continue;
            }
            String name = section.getString(key + ".name", key);
            String shard = section.getString(key + ".shard", "Осколок " + name);
            String keyName = section.getString(key + ".key", "Ключ " + name);
            Material shardItem = HuntMaterials.resolve(section.getString(key + ".shard-item", "BONE"));
            Material keyItem = HuntMaterials.resolve(section.getString(key + ".key-item", "TRIAL_KEY"));
            if (!HuntMaterials.isItem(shardItem)) {
                shardItem = HuntFamily.DEFAULT_SHARD_ITEM;
            }
            if (!HuntMaterials.isItem(keyItem)) {
                keyItem = HuntFamily.DEFAULT_KEY_ITEM;
            }
            double multiplier = clampDouble(section.getDouble(key + ".coin-multiplier", 1.0),
                    0.1, 10.0);
            warnClamped(logger, "families." + key + ".coin-multiplier",
                    1.0, multiplier);
            HuntChest chest = parseChest(section, key, id, logger);
            if (chest == null) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] families." + key + ": нет секции chest — "
                            + "ключи этого семейства некуда применить.");
                }
            }
            out.put(id, new HuntFamily(id, name, shard, keyName, multiplier, shardItem,
                    keyItem, chest));
        }
        return out;
    }

    private static HuntChest parseChest(ConfigurationSection families, String familyKey,
                                        String familyId, Logger logger) {
        ConfigurationSection section = families.getConfigurationSection(familyKey + ".chest");
        if (section == null) {
            return null;
        }
        int[] coins = range(section.get("coins"), 10, 20);
        int[] shards = range(section.get("shards"), 1, 3);
        int[] diamonds = range(section.get("diamonds"), 1, 1);
        double diamondChance = clampDouble(section.getDouble("diamonds-chance", 0.0), 0.0, 100.0);
        List<HuntLoot> loot = parseLoot(section.get("loot"), logger,
                "families." + familyKey + ".chest.loot");
        return new HuntChest(familyId, coins[0], coins[1], shards[0], shards[1],
                diamondChance, diamonds[0], diamonds[1], loot);
    }

    private static List<MobRule> parseMobs(FileConfiguration config,
                                           Map<String, HuntFamily> families, Logger logger) {
        List<MobRule> out = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("mobs");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] mobs." + key + ": неизвестный моб — пропускаю.");
                }
                continue;
            }
            if (!type.isAlive()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] mobs." + key + ": это не живой моб — пропускаю.");
                }
                continue;
            }
            String familyId = HuntFamily.normalize(section.getString(key + ".family", ""));
            if (!families.containsKey(familyId)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] mobs." + key + ": семейство «" + familyId
                            + "» не описано в families — пропускаю.");
                }
                continue;
            }
            int[] coins = range(section.get(key + ".coins"), 1, 3);
            int[] shards = range(section.get(key + ".shards"), 1, 1);
            double shardChance = clampDouble(section.getDouble(key + ".shard-chance", 0.0),
                    0.0, 100.0);
            double keyChance = clampDouble(section.getDouble(key + ".key-chance", 0.0),
                    0.0, 100.0);
            boolean boss = section.getBoolean(key + ".boss", false);
            List<HuntLoot> loot = parseLoot(section.get(key + ".loot"), logger, "mobs." + key + ".loot");
            MobRule rule = new MobRule(type, familyId, coins[0], coins[1], shardChance,
                    shards[0], shards[1], keyChance, boss, loot);
            if (!rule.usable()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] mobs." + key + ": нет ни монет, ни осколков, "
                            + "ни шанса ключа — правило пропущено.");
                }
                continue;
            }
            out.add(rule);
        }
        return out;
    }

    /* -------------------------------- бестиарий -------------------------------- */

    /**
     * Разбирает секцию {@code bestiary}: награды разделов и иконки видов.
     *
     * <p>Разделы строятся по семействам и правилам мобов, поэтому админ не
     * описывает журнал дважды: добавил моба в {@code mobs:} — он появился в
     * бестиарии. Здесь задаются только награды и, при желании, иконки.</p>
     */
    private static HuntBestiary parseBestiary(FileConfiguration config,
                                              Map<String, HuntFamily> families,
                                              List<MobRule> mobs, Logger logger) {
        if (!config.getBoolean("bestiary.enabled", true)) {
            return HuntBestiary.empty();
        }
        Map<String, HuntBestiary.Reward> rewards = new LinkedHashMap<>();
        ConfigurationSection sections = config.getConfigurationSection("bestiary.sections");
        if (sections == null) {
            // Старый config.yml без секции: берём встроенные награды, иначе
            // обновление молча лишило бы игроков подарков.
            rewards.putAll(HuntBestiary.defaultRewards());
        } else {
            for (String key : sections.getKeys(false)) {
                String id = HuntFamily.normalize(key);
                if (!families.containsKey(id)) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Hunt] bestiary.sections." + key
                                + ": семейства нет в families — награда пропущена.");
                    }
                    continue;
                }
                int coins = (int) clamp(sections.getLong(key + ".coins", 0L), 0L, 1_000_000L);
                int shards = (int) clamp(sections.getLong(key + ".shards", 0L), 0L, 10_000L);
                int keys = (int) clamp(sections.getLong(key + ".keys", 0L), 0L, 100L);
                rewards.put(id, new HuntBestiary.Reward(coins, shards, keys));
            }
        }
        Map<EntityType, Material> icons = new LinkedHashMap<>();
        ConfigurationSection iconSection = config.getConfigurationSection("bestiary.icons");
        if (iconSection != null) {
            for (String key : iconSection.getKeys(false)) {
                EntityType type;
                try {
                    type = EntityType.valueOf(key.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Hunt] bestiary.icons." + key
                                + ": неизвестный моб — иконка пропущена.");
                    }
                    continue;
                }
                Material icon = HuntMaterials.resolve(iconSection.getString(key));
                if (!HuntMaterials.isItem(icon)) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Hunt] bestiary.icons." + key
                                + ": неизвестный материал «" + iconSection.getString(key)
                                + "» — взята иконка по умолчанию.");
                    }
                    continue;
                }
                icons.put(type, icon);
            }
        }
        int fullCoins = (int) clamp(config.getLong("bestiary.full-coins", 1500L),
                0L, 1_000_000L);
        HuntBestiary bestiary = HuntBestiary.of(mobs, families, rewards, icons, fullCoins);
        if (bestiary.empty() && !mobs.isEmpty() && logger != null) {
            logger.warning("[CHRDK_Hunt] Бестиарий пуст: ни одно правило мобов не попало "
                    + "в журнал. Проверьте секции families и mobs.");
        }
        return bestiary;
    }

    /* --------------------------------- лавка ----------------------------------- */

    private static List<HuntShopOffer> parseShop(FileConfiguration config, Logger logger) {
        List<HuntShopOffer> out = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("shop");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            String id = HuntFamily.normalize(key);
            Material material = HuntMaterials.resolve(section.getString(key + ".item", key));
            if (!HuntMaterials.isItem(material)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] shop." + key + ": неизвестный item — пропускаю.");
                }
                continue;
            }
            int amount = (int) clamp(section.getLong(key + ".amount", 1L), 1L,
                    HuntShopOffer.MAX_AMOUNT);
            int price = (int) clamp(section.getLong(key + ".price", 10L), 1L,
                    HuntShopOffer.MAX_PRICE);
            int dailyLimit = (int) clamp(section.getLong(key + ".daily-limit", 0L), 0L, 1_000L);
            int minEarned = (int) clamp(section.getLong(key + ".min-coins-earned", 0L),
                    0L, Integer.MAX_VALUE);
            if (out.size() >= MAX_MENU_ITEMS) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] shop: больше " + MAX_MENU_ITEMS
                            + " позиций — «" + key + "» не попадёт в меню.");
                }
                continue;
            }
            out.add(new HuntShopOffer(id, material, amount, price, dailyLimit, minEarned));
        }
        return out;
    }

    /* --------------------------------- трофеи ---------------------------------- */

    private static List<HuntTrophy> parseTrophies(FileConfiguration config,
                                                  Map<String, HuntFamily> families, Logger logger) {
        List<HuntTrophy> out = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("trophies");
        if (section == null) {
            return out;
        }
        int order = 0;
        for (String key : section.getKeys(false)) {
            String familyId = HuntFamily.normalize(section.getString(key + ".family", ""));
            if (!families.containsKey(familyId)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] trophies." + key + ": семейства «" + familyId
                            + "» нет — пропускаю.");
                }
                continue;
            }
            Material material = HuntMaterials.resolve(section.getString(key + ".item", key));
            if (!HuntMaterials.isItem(material)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] trophies." + key + ": неизвестный item — пропускаю.");
                }
                continue;
            }
            if (out.size() >= MAX_MENU_ITEMS) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] trophies: больше " + MAX_MENU_ITEMS
                            + " трофеев — «" + key + "» не попадёт в меню.");
                }
                continue;
            }
            String name = section.getString(key + ".name", key);
            List<String> lore = new ArrayList<>();
            for (String line : section.getStringList(key + ".lore")) {
                if (line != null && !line.isBlank()) {
                    lore.add(line.trim());
                }
            }
            int shards = (int) clamp(section.getLong(key + ".shards", 1L), 0L,
                    HuntTrophy.MAX_SHARDS);
            int coins = (int) clamp(section.getLong(key + ".coins", 0L), 0L,
                    HuntTrophy.MAX_COINS);
            int minEarned = (int) clamp(section.getLong(key + ".min-coins-earned", 0L),
                    0L, Integer.MAX_VALUE);
            out.add(new HuntTrophy(key, familyId, material, name, lore, shards, coins,
                    minEarned, order++));
        }
        return out;
    }

    /* --------------------------------- звания ---------------------------------- */

    private static TreeMap<Integer, String> parseRanks(FileConfiguration config, Logger logger) {
        TreeMap<Integer, String> out = new TreeMap<>();
        ConfigurationSection section = config.getConfigurationSection("ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Integer threshold = parseInt(key);
                String id = section.getString(key, "");
                if (threshold == null || threshold < 0 || id == null || id.isBlank()) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Hunt] ranks." + key + ": нужен неотрицательный "
                                + "порог и имя звания — пропускаю.");
                    }
                    continue;
                }
                out.put(threshold, id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? new TreeMap<>(defaultRanks()) : out;
    }

    /* ------------------------------- вспомогательное --------------------------- */

    private static List<HuntLoot> parseLoot(Object raw, Logger logger, String path) {
        List<HuntLoot> out = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) {
            return out;
        }
        int index = 0;
        for (Object entry : rows) {
            index++;
            if (!(entry instanceof Map<?, ?> row)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] " + path + "[" + index + "]: ожидался список "
                            + "полей item/amount/chance — пропускаю.");
                }
                continue;
            }
            Material material = HuntMaterials.resolve(string(row.get("item")));
            if (!HuntMaterials.isItem(material)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Hunt] " + path + "[" + index + "]: неизвестный item «"
                            + string(row.get("item")) + "» — пропускаю.");
                }
                continue;
            }
            int[] amount = range(row.get("amount"), 1, 1);
            double chance = Double.isFinite(number(row.get("chance"), 100.0))
                    ? Math.clamp(number(row.get("chance"), 100.0), 0.0, 100.0) : 100.0;
            out.add(new HuntLoot(material, amount[0], amount[1], chance));
        }
        return out;
    }

    /**
     * Диапазон из конфига: {@code [min, max]}, одно число или пустое значение.
     * Так один и тот же разбор годится и для списков YAML, и для одиночного числа.
     */
    static int[] range(Object raw, int defaultMin, int defaultMax) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            int min = (int) clamp(longOf(list.get(0), defaultMin), Integer.MIN_VALUE,
                    Integer.MAX_VALUE);
            int max = list.size() > 1
                    ? (int) clamp(longOf(list.get(1), min), Integer.MIN_VALUE, Integer.MAX_VALUE)
                    : min;
            return new int[]{Math.min(min, max), Math.max(min, max)};
        }
        if (raw instanceof Number number) {
            int value = number.intValue();
            return new int[]{value, value};
        }
        return new int[]{defaultMin, Math.max(defaultMin, defaultMax)};
    }

    private static long longOf(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double clampDouble(double value, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : min;
    }

    static long clamp(long value, long min, long max) {
        return Math.clamp(value, min, max);
    }

    private static void warnClamped(Logger logger, String path, long requested, long actual) {
        if (logger != null && requested != actual) {
            logger.warning("[CHRDK_Hunt] " + path + ": значение " + requested
                    + " зажато в " + actual + ".");
        }
    }

    private static void warnClamped(Logger logger, String path, double requested, double actual) {
        if (logger != null && Math.abs(requested - actual) > 1.0e-9) {
            logger.warning("[CHRDK_Hunt] " + path + ": значение " + requested
                    + " зажато в " + actual + ".");
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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

    public boolean actionbar() {
        return actionbar;
    }

    /** Мир выключен для охоты. */
    public boolean worldDisabled(String world) {
        return world != null && disabledWorlds.contains(world.trim().toLowerCase(Locale.ROOT));
    }

    public int coinsPerDay() {
        return coinsPerDay;
    }

    public int exchangePerDay() {
        return exchangePerDay;
    }

    public int coinsPerDiamond() {
        return coinsPerDiamond;
    }

    public int exchangeMin() {
        return exchangeMin;
    }

    public boolean blockSpawners() {
        return blockSpawners;
    }

    public boolean blockEggs() {
        return blockEggs;
    }

    public boolean blockCommand() {
        return blockCommand;
    }

    /** Семейства в порядке конфига. */
    public List<HuntFamily> families() {
        return List.copyOf(families.values());
    }

    public HuntFamily family(String id) {
        return id == null ? null : families.get(HuntFamily.normalize(id));
    }

    /** Есть ли семейство с таким идом. */
    public boolean hasFamily(String id) {
        return family(id) != null;
    }

    public List<MobRule> mobRules() {
        return mobRules;
    }

    /** Правило для конкретного типа моба или {@code null}. */
    public MobRule ruleFor(EntityType type) {
        return type == null ? null : rulesByType.get(type);
    }

    public List<HuntShopOffer> shop() {
        return shop;
    }

    public HuntShopOffer offer(String id) {
        return id == null ? null : shopById.get(HuntFamily.normalize(id));
    }

    public List<HuntTrophy> trophies() {
        return trophies;
    }

    public HuntTrophy trophy(String id) {
        return id == null ? null : trophyById.get(HuntFamily.normalize(id));
    }

    /** Трофеи одного семейства в порядке меню. */
    public List<HuntTrophy> trophiesOf(String familyId) {
        List<HuntTrophy> out = new ArrayList<>();
        for (HuntTrophy trophy : trophies) {
            if (trophy.familyId().equals(HuntFamily.normalize(familyId))) {
                out.add(trophy);
            }
        }
        return out;
    }

    /** Журнал видов: разделы, награды и иконки ({@link HuntBestiary#empty()} — выключен). */
    public HuntBestiary bestiary() {
        return bestiary;
    }

    public TreeMap<Integer, String> ranks() {
        return new TreeMap<>(ranks);
    }

    /** Звание по заработанным за всё время монетам. */
    public String rankId(long coinsEarned) {
        Map.Entry<Integer, String> entry = ranks.floorEntry((int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, coinsEarned)));
        return entry == null ? "novice" : entry.getValue();
    }

    /** Порог следующего звания или -1, если выше некуда. */
    public int nextRankThreshold(long coinsEarned) {
        Integer next = ranks.higherKey((int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, coinsEarned)));
        return next == null ? -1 : next;
    }

    /** Ид следующего звания или {@code null}. */
    public String nextRankId(long coinsEarned) {
        Integer next = ranks.higherKey((int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, coinsEarned)));
        return next == null ? null : ranks.get(next);
    }

    public int lastRankThreshold() {
        return ranks.isEmpty() ? 0 : ranks.lastKey();
    }

    /** Звания по умолчанию — для конфига без секции ranks и для тестов. */
    public static TreeMap<Integer, String> defaultRanks() {
        TreeMap<Integer, String> out = new TreeMap<>();
        out.put(0, "novice");
        out.put(300, "tracker");
        out.put(1_200, "hunter");
        out.put(4_000, "master");
        out.put(10_000, "legend");
        return out;
    }
}
