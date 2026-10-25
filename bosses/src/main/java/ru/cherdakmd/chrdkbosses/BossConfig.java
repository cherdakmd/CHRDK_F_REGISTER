package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} боссов и зон (1.4.162).
 *
 * <p>Опечатка в имени моба или материала не должна ронять плагин и не должна
 * тихо превращать босса в неубиваемого: непонятная запись пропускается с
 * предупреждением. Босс без арены, фаз или награды в мир не попадает — иначе
 * игрок увидел бы в меню то, чего не существует.</p>
 */
public final class BossConfig {

    /** Потолок числа боссов. */
    public static final int MAX_BOSSES = 16;
    /** Потолок числа зон. */
    public static final int MAX_ZONES = 16;
    /** Потолок частоты проверки автопланировщика. */
    public static final int MAX_CHECK_SECONDS = 300;

    private final boolean enabled;
    private final String locale;
    private final boolean autoSpawn;
    private final boolean bossbar;
    private final boolean actionbar;
    private final int checkSeconds;
    private final Map<String, BossDefinition> bosses;
    private final Map<String, DangerZone> zones;

    private BossConfig(boolean enabled, String locale, boolean autoSpawn, boolean bossbar,
                       boolean actionbar, int checkSeconds, Map<String, BossDefinition> bosses,
                       Map<String, DangerZone> zones) {
        this.enabled = enabled;
        this.locale = locale;
        this.autoSpawn = autoSpawn;
        this.bossbar = bossbar;
        this.actionbar = actionbar;
        this.checkSeconds = checkSeconds;
        this.bosses = Collections.unmodifiableMap(new LinkedHashMap<>(bosses));
        this.zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
    }

    /** Пустая конфигурация: плагин работает, но боссов и зон нет. */
    public static BossConfig defaults() {
        return new BossConfig(true, "ru", false, true, true, 60, Map.of(), Map.of());
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static BossConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;

        boolean enabled = source.getBoolean("general.enabled", true);
        String locale = source.getString("general.locale", "ru");
        locale = locale == null || locale.isBlank() ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        boolean autoSpawn = source.getBoolean("general.auto-spawn", false);
        boolean bossbar = source.getBoolean("general.bossbar", true);
        boolean actionbar = source.getBoolean("general.actionbar", true);
        int checkSeconds = clamp(source, "general.check-seconds", 60, 10, MAX_CHECK_SECONDS, logger);

        Map<String, BossDefinition> bosses = parseBosses(source, logger);
        Map<String, DangerZone> zones = parseZones(source, logger);
        if (enabled && bosses.isEmpty() && logger != null) {
            logger.warning("[CHRDK_Bosses] В config.yml нет ни одного рабочего босса — "
                    + "авто-спавн ничего не сделает. Проверьте секцию bosses.");
        }
        return new BossConfig(enabled, locale, autoSpawn, bossbar, actionbar, checkSeconds,
                bosses, zones);
    }

    /* ---------------------------------- боссы --------------------------------- */

    private static Map<String, BossDefinition> parseBosses(FileConfiguration config, Logger logger) {
        Map<String, BossDefinition> out = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("bosses");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (out.size() >= MAX_BOSSES) {
                if (logger != null) {
                    logger.warning("[CHRDK_Bosses] Боссов больше " + MAX_BOSSES
                            + " — лишние пропущены.");
                }
                break;
            }
            BossDefinition boss = parseBoss(section, key, logger);
            if (boss == null) {
                continue;
            }
            out.put(boss.id(), boss);
        }
        return out;
    }

    private static BossDefinition parseBoss(ConfigurationSection section, String key, Logger logger) {
        String path = "bosses." + key;
        String id = key.trim().toLowerCase(Locale.ROOT);
        EntityType entity = entity(section.getString(key + ".entity", "ZOMBIE"));
        if (entity == null) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ": неизвестный моб «"
                        + section.getString(key + ".entity") + "» — босс пропущен.");
            }
            return null;
        }
        Material icon = material(section.getString(key + ".icon", "NETHERITE_SWORD"));
        if (icon == null) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ": неизвестная иконка — взята NETHERITE_SWORD.");
            }
            icon = Material.NETHERITE_SWORD;
        }
        Arena arena = new Arena(section.getString(key + ".arena.world", ""),
                section.getDouble(key + ".arena.x", 0.0),
                section.getDouble(key + ".arena.y", Arena.AUTO_HEIGHT),
                section.getDouble(key + ".arena.z", 0.0),
                section.getDouble(key + ".arena.radius", 12.0));

        List<BossPhase> phases = new ArrayList<>();
        for (Map<?, ?> row : section.getMapList(key + ".phases")) {
            BossPhase phase = parsePhase(row, logger, path);
            if (phase != null) {
                phases.add(phase);
            }
        }
        if (phases.isEmpty()) {
            phases = BossPhase.normalize(List.of());
        }

        BossReward reward = parseReward(section, key, logger, path);
        BossDefinition boss = new BossDefinition(id,
                section.getString(key + ".name", key),
                section.getString(key + ".description", ""),
                entity,
                section.getDouble(key + ".health", 200.0),
                icon,
                section.getBoolean(key + ".glow", true),
                arena,
                phases,
                reward,
                (int) section.getLong(key + ".spawn-interval-seconds", 1_800L),
                (int) section.getLong(key + ".duration-seconds", 1_800L),
                (int) section.getLong(key + ".min-players", 2L),
                section.getBoolean(key + ".auto", true));
        if (!boss.usable()) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path
                        + ": нет арены, фаз или награды — босс пропущен.");
            }
            return null;
        }
        return boss;
    }

    private static BossPhase parsePhase(Map<?, ?> row, Logger logger, String path) {
        if (row == null) {
            return null;
        }
        int at = (int) number(row.get("at"), 100);
        String name = string(row.get("name"));
        double damage = number(row.get("damage-multiplier"), 1.0);
        double speed = number(row.get("speed-multiplier"), 1.0);
        List<BossAbility> abilities = new ArrayList<>();
        Object raw = row.get("abilities");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> map)) {
                    continue;
                }
                BossAbility ability = parseAbility(map, logger, path);
                if (ability != null) {
                    abilities.add(ability);
                }
            }
        }
        return new BossPhase(at, name, damage, speed, abilities);
    }

    private static BossAbility parseAbility(Map<?, ?> row, Logger logger, String path) {
        BossAbility.Type type = BossAbility.Type.parse(string(row.get("type")));
        if (type == null) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ": неизвестная способность «"
                        + string(row.get("type")) + "» — пропущена.");
            }
            return null;
        }
        EntityType entity = null;
        String rawEntity = string(row.get("entity"));
        if (!rawEntity.isBlank()) {
            entity = entity(rawEntity);
            if (entity == null && logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ": неизвестный призыв «"
                        + rawEntity + "» — взята свита по умолчанию.");
            }
        }
        BossAbility ability = new BossAbility(type,
                (int) number(row.get("cooldown-seconds"), 12),
                number(row.get("radius"), 6.0),
                number(row.get("amount"), 4.0),
                entity,
                string(row.get("effect")),
                (int) number(row.get("amplifier"), 0),
                (int) number(row.get("duration-seconds"), 0));
        if (!ability.usable()) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ": способность " + type.id()
                        + " без нужных параметров — пропущена.");
            }
            return null;
        }
        return ability;
    }

    private static BossReward parseReward(ConfigurationSection section, String key, Logger logger,
                                          String path) {
        List<BossReward.Item> items = new ArrayList<>();
        Object raw = section.get(key + ".reward.items");
        if (raw instanceof ConfigurationSection itemSection) {
            // В YAML блок «МАТЕРИАЛ: количество» читается как секция, а не как Map —
            // без этой ветки награда из примеров конфига молча оставалась пустой.
            for (String name : itemSection.getKeys(false)) {
                addRewardItem(items, name, (int) number(itemSection.get(name), 1), logger, path);
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                addRewardItem(items, String.valueOf(entry.getKey()),
                        (int) number(entry.getValue(), 1), logger, path);
            }
        }
        Material trophy = material(section.getString(key + ".reward.trophy", ""));
        if (trophy != null && !isItem(trophy)) {
            trophy = null;
        }
        return new BossReward((int) section.getLong(key + ".reward.xp", 0L), items,
                trophy, section.getString(key + ".reward.trophy-name", ""));
    }

    private static void addRewardItem(List<BossReward.Item> items, String name, int amount,
                                      Logger logger, String path) {
        Material material = material(name);
        if (material == null || !isItem(material)) {
            if (logger != null) {
                logger.warning("[CHRDK_Bosses] " + path + ".reward.items: неизвестный "
                        + "материал «" + name + "» — пропущен.");
            }
            return;
        }
        items.add(new BossReward.Item(material, amount));
    }

    /* ----------------------------------- зоны --------------------------------- */

    private static Map<String, DangerZone> parseZones(FileConfiguration config, Logger logger) {
        Map<String, DangerZone> out = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("zones");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (out.size() >= MAX_ZONES) {
                if (logger != null) {
                    logger.warning("[CHRDK_Bosses] Зон больше " + MAX_ZONES
                            + " — лишние пропущены.");
                }
                break;
            }
            String path = "zones." + key;
            String id = key.trim().toLowerCase(Locale.ROOT);
            String world = section.getString(key + ".world", "");
            if (world == null || world.isBlank()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Bosses] " + path + ": не задан мир — зона пропущена.");
                }
                continue;
            }
            Material icon = material(section.getString(key + ".icon", "MAGMA_BLOCK"));
            if (icon == null) {
                icon = Material.MAGMA_BLOCK;
            }
            List<ZoneEffect> effects = new ArrayList<>();
            for (Map<?, ?> row : section.getMapList(key + ".effects")) {
                if (row == null) {
                    continue;
                }
                ZoneEffect effect = new ZoneEffect(string(row.get("type")),
                        (int) number(row.get("amplifier"), 0),
                        (int) number(row.get("duration-seconds"), 5));
                if (effect.usable()) {
                    effects.add(effect);
                } else if (logger != null) {
                    logger.warning("[CHRDK_Bosses] " + path + ".effects: пустой эффект — пропущен.");
                }
            }
            DangerZone zone = new DangerZone(id,
                    section.getString(key + ".name", key),
                    section.getString(key + ".description", ""),
                    section.getBoolean(key + ".enabled", true),
                    world,
                    section.getDouble(key + ".x", 0.0),
                    section.getDouble(key + ".y", 64.0),
                    section.getDouble(key + ".z", 0.0),
                    section.getDouble(key + ".radius", 24.0),
                    (int) number(section.get(key + ".level"), 1),
                    section.getDouble(key + ".damage-per-second", 0.0),
                    (int) number(section.get(key + ".xp-bonus-percent"), 0),
                    (int) number(section.get(key + ".effect-interval-seconds"), 5),
                    effects,
                    icon);
            out.put(zone.id(), zone);
        }
        return out;
    }

    /* -------------------------------- помощники ------------------------------- */

    static Material material(String name) {
        String key = key(name);
        if (key == null) {
            return null;
        }
        try {
            return Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    static EntityType entity(String name) {
        String key = key(name);
        if (key == null) {
            return null;
        }
        try {
            return EntityType.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    static boolean isItem(Material material) {
        return material != null && material != Material.AIR && material != Material.CAVE_AIR
                && material != Material.VOID_AIR;
    }

    private static String key(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length()).trim();
        }
        return key.isEmpty() ? null : key;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static int clamp(FileConfiguration source, String path, int fallback,
                             int min, int max, Logger logger) {
        long raw = source.getLong(path, fallback);
        int value = (int) Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Bosses] " + path + ": " + raw
                    + " вне границ " + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    /* --------------------------------- доступ --------------------------------- */

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean autoSpawn() {
        return autoSpawn;
    }

    public boolean bossbar() {
        return bossbar;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public int checkSeconds() {
        return checkSeconds;
    }

    public Map<String, BossDefinition> bosses() {
        return bosses;
    }

    public List<BossDefinition> bossList() {
        return List.copyOf(bosses.values());
    }

    public BossDefinition boss(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return bosses.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> bossIds() {
        return List.copyOf(bosses.keySet());
    }

    public Map<String, DangerZone> zones() {
        return zones;
    }

    public List<DangerZone> zoneList() {
        return List.copyOf(zones.values());
    }

    /** Включённые зоны — только они действуют в мире. */
    public List<DangerZone> activeZones() {
        List<DangerZone> out = new ArrayList<>();
        for (DangerZone zone : zones.values()) {
            if (zone.enabled()) {
                out.add(zone);
            }
        }
        return out;
    }

    public DangerZone zone(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return zones.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> zoneIds() {
        return List.copyOf(zones.keySet());
    }
}
