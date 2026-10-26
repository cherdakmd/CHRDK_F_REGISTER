package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор config.yml: по-артефактные настройки (вкл/кулдаун/цена в уровнях),
 * источники и уникальные свойства артефактов (волна 19).
 * Дефолты — из {@link ArtifactRegistry}; значения из файла клампятся.
 */
public final class ArtifactsConfig {

    public record Override(boolean enabled, int cooldownSeconds, int xpCostLevels) {
    }

    /**
     * Настройки уникальных свойств артефактов (1.4.129).
     *
     * @param enabled            включены ли свойства вообще
     * @param chancePercent      0..100 — шанс, что артефакт получит свойство
     * @param maxPerItem         0..3 — потолок свойств на предмет
     * @param maxLevel           1..3 — потолок уровня свойства
     * @param crossChancePercent 0..100 — шанс «чужой» полярности
     */
    public record Traits(boolean enabled, int chancePercent, int maxPerItem, int maxLevel,
                         int crossChancePercent) {

        public static final Traits DEFAULT = new Traits(true, 55, 2, 3, 12);
    }

    /**
     * Обратная связь при использовании артефактов (1.4.130).
     *
     * @param actionbar           показывать экшнбар при активации, отказе и проклятии
     * @param sounds              проигрывать звуки активации/отказа/находки
     * @param curseMessageSeconds не чаще одного сообщения о проклятии в чат (сек);
     *                            0 — без ограничения (каждый сработавший триггер)
     */
    public record Feedback(boolean actionbar, boolean sounds, int curseMessageSeconds) {

        public static final Feedback DEFAULT = new Feedback(true, true, 10);
    }

    /**
     * Коллекция артефактов (1.4.130).
     *
     * @param enabled включена ли коллекция и её награды
     * @param rewards пороги наград (процент каталога → дни ранга + алмазы)
     */
    public record Collection(boolean enabled, List<CollectionRewards.Tier> rewards) {

        public static final Collection DEFAULT =
                new Collection(true, CollectionRewards.defaults());
    }

    private final boolean requiresAuth;
    private final int refreshTicks;
    private final Map<String, Override> overrides;
    private final Map<String, Double> chestChances;
    private final Map<String, Double> mobChances;
    private final double fishingTreasurePercent;
    private final int goodSideWeightPercent;
    private final WorldPolicy worldPolicy;
    private final Traits traits;
    private final Feedback feedback;
    private final Collection collection;

    private ArtifactsConfig(boolean requiresAuth, int refreshTicks, Map<String, Override> overrides,
                            Map<String, Double> chestChances, Map<String, Double> mobChances,
                            double fishingTreasurePercent, int goodSideWeightPercent,
                            WorldPolicy worldPolicy, Traits traits, Feedback feedback,
                            Collection collection) {
        this.requiresAuth = requiresAuth;
        this.refreshTicks = refreshTicks;
        this.overrides = overrides;
        this.chestChances = chestChances;
        this.mobChances = mobChances;
        this.fishingTreasurePercent = fishingTreasurePercent;
        this.goodSideWeightPercent = goodSideWeightPercent;
        this.worldPolicy = worldPolicy;
        this.traits = traits;
        this.feedback = feedback;
        this.collection = collection;
    }

    public static ArtifactsConfig parse(FileConfiguration cfg, Logger logger) {
        boolean requiresAuth = cfg.getBoolean("general.require-auth", true);
        int refreshTicks = clamp(cfg.getInt("general.passive-refresh-ticks", 40), 10, 200);
        Map<String, Override> overrides = new HashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("artifacts");
        for (ArtifactDef def : ArtifactRegistry.all()) {
            boolean enabled = true;
            int cooldown = def.cooldownSeconds();
            int xp = def.xpCostLevels();
            if (root != null) {
                ConfigurationSection section = root.getConfigurationSection(def.id());
                if (section != null) {
                    enabled = section.getBoolean("enabled", true);
                    cooldown = section.getInt("cooldown-seconds", def.cooldownSeconds());
                    xp = section.getInt("xp-cost-levels", def.xpCostLevels());
                }
            }
            overrides.put(def.id(), new Override(enabled,
                    clamp(cooldown, 0, 86_400), clamp(xp, 0, 100)));
        }
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (ArtifactRegistry.byId(key) == null) {
                    logger.warning("[CHRDK_Artifacts] Неизвестный артефакт в config.yml: " + key);
                }
            }
        }
        Map<String, Double> chestChances = readChances(
                cfg.getConfigurationSection("sources.chests"), logger, "chests");
        Map<String, Double> mobChances = readChances(
                cfg.getConfigurationSection("sources.mobs"), logger, "mobs");
        double fishing = clampPercent(cfg.getDouble("sources.fishing-treasure-percent", 3));
        int goodWeight = clamp(cfg.getInt("sources.good-side-chance-percent", 70), 0, 100);
        WorldPolicy worldPolicy = new WorldPolicy(
                cfg.getBoolean("world-effects.weather", true),
                cfg.getBoolean("world-effects.time", true),
                new java.util.HashSet<>(cfg.getStringList("world-effects.worlds")));
        return new ArtifactsConfig(requiresAuth, refreshTicks, Map.copyOf(overrides),
                Map.copyOf(chestChances), Map.copyOf(mobChances), fishing, goodWeight,
                worldPolicy, parseTraits(cfg, logger), parseFeedback(cfg, logger),
                parseCollection(cfg, logger));
    }

    /** Секция {@code traits.*} (1.4.129): дефолты при отсутствии, клампы при кривых значениях. */
    static Traits parseTraits(FileConfiguration cfg, Logger logger) {
        if (cfg == null) {
            return Traits.DEFAULT;
        }
        boolean enabled = cfg.getBoolean("traits.enabled", true);
        int chance = clamp(cfg.getInt("traits.chance-percent", 55), 0, 100);
        int maxPerItem = clamp(cfg.getInt("traits.max-per-item", 2), 0, 3);
        int maxLevel = clamp(cfg.getInt("traits.max-level", 3), 1, 3);
        int cross = clamp(cfg.getInt("traits.cross-chance-percent", 12), 0, 100);
        if (logger != null) {
            warnIfClamped(logger, "traits.chance-percent", cfg.getInt("traits.chance-percent", 55),
                    0, 100, chance);
            warnIfClamped(logger, "traits.max-per-item", cfg.getInt("traits.max-per-item", 2),
                    0, 3, maxPerItem);
            warnIfClamped(logger, "traits.max-level", cfg.getInt("traits.max-level", 3),
                    1, 3, maxLevel);
            warnIfClamped(logger, "traits.cross-chance-percent",
                    cfg.getInt("traits.cross-chance-percent", 12), 0, 100, cross);
        }
        return new Traits(enabled, chance, maxPerItem, maxLevel, cross);
    }

    /** Секция {@code feedback.*} (1.4.130): экшнбар, звуки и антиспам проклятий. */
    static Feedback parseFeedback(FileConfiguration cfg, Logger logger) {
        if (cfg == null) {
            return Feedback.DEFAULT;
        }
        boolean actionbar = cfg.getBoolean("feedback.actionbar", true);
        boolean sounds = cfg.getBoolean("feedback.sounds", true);
        int curse = cfg.getInt("feedback.curse-message-seconds", 10);
        int applied = clamp(curse, 0, 3600);
        if (logger != null) {
            warnIfClamped(logger, "feedback.curse-message-seconds", curse, 0, 3600, applied);
        }
        return new Feedback(actionbar, sounds, applied);
    }

    /** Секция {@code collection.*} (1.4.130): включена ли коллекция и её пороги наград. */
    static Collection parseCollection(FileConfiguration cfg, Logger logger) {
        if (cfg == null) {
            return Collection.DEFAULT;
        }
        boolean enabled = cfg.getBoolean("collection.enabled", true);
        ConfigurationSection rewards = cfg.getConfigurationSection("collection.rewards");
        List<CollectionRewards.Tier> tiers = new ArrayList<>();
        if (rewards != null) {
            for (String key : rewards.getKeys(false)) {
                Integer percent = parseInt(key);
                if (percent == null) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Artifacts] collection.rewards." + key
                                + ": ключ должен быть процентом (1..100) — пропускаю.");
                    }
                    continue;
                }
                // 1.4.184: часы вместо суток; старый ключ pass-days читается как часы.
                String hoursKey = rewards.contains(key + ".pass-hours")
                        ? key + ".pass-hours" : key + ".pass-days";
                int hours = rewards.getInt(hoursKey, 0);
                int diamonds = rewards.getInt(key + ".diamonds", 0);
                if (logger != null) {
                    warnIfClamped(logger, "collection.rewards." + hoursKey, hours,
                            0, 24, Math.max(0, Math.min(24, hours)));
                    warnIfClamped(logger, "collection.rewards." + key + ".diamonds", diamonds,
                            0, 576, Math.max(0, Math.min(576, diamonds)));
                }
                tiers.add(new CollectionRewards.Tier(percent, hours, diamonds));
            }
        }
        List<CollectionRewards.Tier> normalized = CollectionRewards.normalize(tiers);
        return new Collection(enabled,
                normalized.isEmpty() ? CollectionRewards.defaults() : normalized);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void warnIfClamped(Logger logger, String key, int value, int min, int max,
                                      int applied) {
        if (value != applied) {
            logger.warning("[CHRDK_Artifacts] " + key + " = " + value + " вне " + min + ".."
                    + max + " — использую " + applied);
        }
    }

    private static Map<String, Double> readChances(ConfigurationSection section, Logger logger,
                                                   String what) {
        Map<String, Double> out = new HashMap<>();
        if (section == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            if (!(entry.getValue() instanceof Number number)) {
                logger.warning("[CHRDK_Artifacts] sources." + what + "." + entry.getKey()
                        + ": шанс должен быть числом — пропускаю.");
                continue;
            }
            out.put(entry.getKey(), clampPercent(number.doubleValue()));
        }
        return out;
    }

    private static double clampPercent(double value) {
        return Math.min(100.0, Math.max(0.0, value));
    }

    public boolean requiresAuth() {
        return requiresAuth;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public boolean isEnabled(String artifactId) {
        Override ov = overrides.get(artifactId);
        return ov == null || ov.enabled();
    }

    public int cooldownSeconds(ArtifactDef def) {
        Override ov = overrides.get(def.id());
        return ov == null ? def.cooldownSeconds() : ov.cooldownSeconds();
    }

    public int xpCostLevels(ArtifactDef def) {
        Override ov = overrides.get(def.id());
        return ov == null ? def.xpCostLevels() : ov.xpCostLevels();
    }

    /**
     * Шанс найти артефакт в сундуке типа таблицы (%): подходит и полное
     * namespaced-имя, и короткое имя последнего сегмента. Короткое имя важно для
     * таблиц датапаков (например, датапак с сокровищами при рудокопке кладёт лут
     * в свои таблицы): `mine_treasure:chests/ancient_city` совпадёт с
     * `minecraft:chests/ancient_city` из конфига.
     */
    public double chestChancePercent(String lootTableKey) {
        if (lootTableKey == null) {
            return 0.0;
        }
        Double direct = chestChances.get(lootTableKey);
        if (direct != null) {
            return direct;
        }
        String shortName = shortName(lootTableKey);
        Double byFullShort = chestChances.get(shortName);
        if (byFullShort != null) {
            return byFullShort;
        }
        for (Map.Entry<String, Double> entry : chestChances.entrySet()) {
            if (shortName(entry.getKey()).equals(shortName)) {
                return entry.getValue();
            }
        }
        return 0.0;
    }

    /** Последний сегмент ключа таблицы: `minecraft:chests/ancient_city` → `ancient_city`. */
    static String shortName(String lootTableKey) {
        return lootTableKey.substring(lootTableKey.lastIndexOf('/') + 1)
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** Шанс дропа артефакта с типа существа (%). */
    public double mobChancePercent(org.bukkit.entity.EntityType type) {
        return type == null ? 0.0 : mobChances.getOrDefault(type.name(), 0.0);
    }

    public double fishingTreasurePercent() {
        return fishingTreasurePercent;
    }

    public int goodSideWeightPercent() {
        return goodSideWeightPercent;
    }

    /** Политика мировых эффектов (погода/время) из {@code world-effects}. */
    public WorldPolicy worldPolicy() {
        return worldPolicy;
    }

    /** Уникальные свойства артефактов (1.4.129). */
    public Traits traits() {
        return traits;
    }

    /** Обратная связь артефактов (1.4.130). */
    public Feedback feedback() {
        return feedback;
    }

    /** Коллекция артефактов и награды (1.4.130). */
    public Collection collection() {
        return collection;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
