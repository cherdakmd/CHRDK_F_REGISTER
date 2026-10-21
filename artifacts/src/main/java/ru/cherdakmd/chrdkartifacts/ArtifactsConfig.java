package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
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

    private final boolean requiresAuth;
    private final int refreshTicks;
    private final Map<String, Override> overrides;
    private final Map<String, Double> chestChances;
    private final Map<String, Double> mobChances;
    private final double fishingTreasurePercent;
    private final int goodSideWeightPercent;
    private final WorldPolicy worldPolicy;
    private final Traits traits;

    private ArtifactsConfig(boolean requiresAuth, int refreshTicks, Map<String, Override> overrides,
                            Map<String, Double> chestChances, Map<String, Double> mobChances,
                            double fishingTreasurePercent, int goodSideWeightPercent,
                            WorldPolicy worldPolicy, Traits traits) {
        this.requiresAuth = requiresAuth;
        this.refreshTicks = refreshTicks;
        this.overrides = overrides;
        this.chestChances = chestChances;
        this.mobChances = mobChances;
        this.fishingTreasurePercent = fishingTreasurePercent;
        this.goodSideWeightPercent = goodSideWeightPercent;
        this.worldPolicy = worldPolicy;
        this.traits = traits;
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
                worldPolicy, parseTraits(cfg, logger));
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

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
