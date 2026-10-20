package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Снимок конфигурации. */
public record TierForgeConfig(
        int rollOnCraftChancePercent,
        List<TierDef.Snapshot> tiers,
        boolean anvilEnabled,
        int anvilBaseXp,
        boolean craftArmor,
        boolean craftWeapons,
        boolean craftTools,
        boolean rerollEnabled,
        String rerollCatalyst,
        int rerollBaseXp,
        boolean mobDropEnabled,
        int mobDropChancePercent,
        int mobWeaponsChancePercent,
        int mobArmorChancePercent,
        int mobToolsChancePercent,
        java.util.Set<String> mobWorldBlacklist,
        boolean mobPlayerKillOnly,
        boolean identifyUnidentified,
        int identifyScanTicks,
        int identifyScanPlayersPerTick,
        boolean identifySounds,
        int passBonusCraftPercent,
        int weaponsChancePercent,
        int armorChancePercent,
        int toolsChancePercent,
        java.util.Set<String> materialBlacklist,
        double mobBossMultiplier,
        boolean rerollNoDowngrade,
        boolean improveEnabled,
        int improveBaseXp,
        int statsBackupCopies,
        java.util.Map<String, String> improveMaterials,
        boolean namesEnabled,
        java.util.List<String> namesWeapons,
        java.util.List<String> namesArmor,
        java.util.List<String> namesTools,
        boolean propertiesEnabled,
        int propertiesPerItem) {

    public TierDef.Snapshot find(String id) {
        return tiers.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    public static TierForgeConfig parse(FileConfiguration config, Logger logger) {
        int chance = clamp(config.getInt("roll-on-craft-chance-percent", 100), 0, 100, logger,
                "roll-on-craft-chance-percent");
        List<TierDef.Snapshot> tiers = TierDef.parse(config, logger);
        boolean anvil = config.getBoolean("anvil.enabled", true);
        int baseXp = clamp(config.getInt("anvil.base-xp-cost", 3), 1, 30, logger, "anvil.base-xp-cost");

        boolean reroll = config.getBoolean("reroll.enabled", true);
        String catalyst = config.getString("reroll.catalyst", "AMETHYST_SHARD");
        if (catalyst == null || catalyst.isBlank()) {
            catalyst = "AMETHYST_SHARD";
        }
        catalyst = catalyst.trim().toUpperCase(Locale.ROOT);
        if (Material.matchMaterial(catalyst) == null) {
            logger.warning("config: reroll.catalyst '" + catalyst
                    + "' — неизвестный материал, перекат отключён");
            reroll = false;
        }
        int rerollXp = clamp(config.getInt("reroll.base-xp-cost", 5), 1, 39, logger,
                "reroll.base-xp-cost");

        boolean mobDrop = config.getBoolean("mob-drop.enabled", true);
        int mobChance = clamp(config.getInt("mob-drop.chance-percent", 15), 0, 100, logger,
                "mob-drop.chance-percent");
        // 1.4.87: свой шанс дропа для группы (−1 = общий).
        int mobWeapons = kindChance(config.getInt("mob-drop.weapons-chance-percent", -1), logger,
                "mob-drop.weapons-chance-percent");
        int mobArmor = kindChance(config.getInt("mob-drop.armor-chance-percent", -1), logger,
                "mob-drop.armor-chance-percent");
        int mobTools = kindChance(config.getInt("mob-drop.tools-chance-percent", -1), logger,
                "mob-drop.tools-chance-percent");
        // 1.4.80: множитель босса больше не принимает 0/отрицательные и абсурдные
        // значения (NaN из YAML тоже отсекаем) — иначе шанс дропа молча становился нулевым.
        double bossMultiplier = config.getDouble("mob-drop.boss-multiplier", 2.0);
        if (!Double.isFinite(bossMultiplier) || bossMultiplier < 1.0 || bossMultiplier > 20.0) {
            logger.warning("config: mob-drop.boss-multiplier вне диапазона 1.0..20.0, "
                    + "применена граница");
            bossMultiplier = Math.max(1.0, Math.min(20.0,
                    Double.isFinite(bossMultiplier) ? bossMultiplier : 2.0));
        }

        // 1.4.83: миры, где дроп не получает накат, и требование «убил игрок».
        java.util.Set<String> mobWorlds = new java.util.HashSet<>();
        for (String world : config.getStringList("mob-drop.world-blacklist")) {
            if (world != null && !world.isBlank()) {
                mobWorlds.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }
        boolean mobKillOnly = config.getBoolean("mob-drop.player-kill-only", false);

        java.util.Set<String> blacklist = new java.util.HashSet<>();
        for (String material : config.getStringList("craft.material-blacklist")) {
            if (material != null && !material.isBlank()) {
                blacklist.add(material.trim().toUpperCase(Locale.ROOT));
            }
        }

        // --- кузнечные улучшения (1.4.55) ---
        boolean improve = config.getBoolean("improve.enabled", true);
        int improveXp = clamp(config.getInt("improve.base-xp-cost", 3), 1, 38, logger,
                "improve.base-xp-cost");
        // 1.4.95: сколько резервных копий stats.yml хранить перед перезаписью.
        int statsBackups = config.getInt("stats.backup-copies", 3);
        int statsBackupCopies = StatsBackup.clampKeep(statsBackups);
        if (statsBackupCopies != statsBackups) {
            logger.warning("config: stats.backup-copies " + statsBackups + " вне 0.."
                    + StatsBackup.MAX_COPIES + " — используется " + statsBackupCopies);
        }
        java.util.Map<String, String> improveMaterials = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection materialsSection =
                config.getConfigurationSection("improve.materials");
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            String raw = materialsSection == null ? def.material()
                    : materialsSection.getString(def.id(), def.material());
            if (raw == null || raw.isBlank()) {
                raw = def.material();
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (Material.matchMaterial(normalized) == null) {
                logger.warning("config: improve.materials." + def.id() + " '" + normalized
                        + "' — неизвестный материал, используется умолчание " + def.material());
                normalized = def.material();
            }
            improveMaterials.put(def.id(), normalized);
        }

        // --- клички кованных предметов (1.4.55) ---
        boolean naming = config.getBoolean("names.enabled", true);

        // --- уникальные свойства тиров (1.4.118) ---
        boolean properties = config.getBoolean("properties.enabled", true);
        int propertiesPerItem = clamp(config.getInt("properties.per-item",
                        TierProperties.DEFAULT_PER_ITEM), 1, TierProperties.MAX_PER_ITEM,
                logger, "properties.per-item");

        return new TierForgeConfig(chance, tiers, anvil, baseXp,
                config.getBoolean("craft.armor", true),
                config.getBoolean("craft.weapons", true),
                config.getBoolean("craft.tools", true),
                reroll, catalyst, rerollXp, mobDrop, mobChance,
                mobWeapons, mobArmor, mobTools, mobWorlds, mobKillOnly,
                config.getBoolean("identify.enabled", true),
                // 1.4.80: верхняя граница и внятное предупреждение — скан «раз в тик»
                // ронял TPS, а нулевой/крошечный период молча не применялся.
                clamp(config.getInt("identify.scan-ticks", 10), 10, 1200, logger,
                        "identify.scan-ticks"),
                // 1.4.86: скан идёт порциями, а не «все игроки разом» — иначе на
                // большом онлайне один тик сканировал сотни инвентарей.
                clamp(config.getInt("identify.scan-players-per-tick", 5), 1, 100, logger,
                        "identify.scan-players-per-tick"),
                config.getBoolean("identify.sounds", true),
                clamp(config.getInt("identify.pass-bonus-chance-percent", 0), 0, 100, logger,
                        "identify.pass-bonus-chance-percent"),
                kindChance(config.getInt("craft.weapons-chance-percent", -1), logger,
                        "craft.weapons-chance-percent"),
                kindChance(config.getInt("craft.armor-chance-percent", -1), logger,
                        "craft.armor-chance-percent"),
                kindChance(config.getInt("craft.tools-chance-percent", -1), logger,
                        "craft.tools-chance-percent"),
                blacklist,
                bossMultiplier,
                config.getBoolean("reroll.no-downgrade", true),
                improve, improveXp, statsBackupCopies, improveMaterials, naming,
                readNames(config, "names.weapons", ForgeNamePool.DEFAULT_WEAPONS),
                readNames(config, "names.armor", ForgeNamePool.DEFAULT_ARMOR),
                readNames(config, "names.tools", ForgeNamePool.DEFAULT_TOOLS),
                properties, propertiesPerItem);
    }

    /** Список имён из конфига с дефолтом (пустая/битая секция = умолчание). */
    private static java.util.List<String> readNames(org.bukkit.configuration.file.FileConfiguration config,
                                                    String key, java.util.List<String> fallback) {
        java.util.List<String> cleaned = config.getStringList(key).stream()
                .filter(line -> line != null && !line.isBlank())
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /** Шанс наката при крафте для вида предмета (%). -1 в конфиге значит «по общему». */
    public int craftChanceFor(ItemKind kind) {
        if (kind == null) return 0;
        if (kind.isWeapon()) return weaponsChancePercent >= 0 ? weaponsChancePercent : rollOnCraftChancePercent;
        if (kind.isArmor()) return armorChancePercent >= 0 ? armorChancePercent : rollOnCraftChancePercent;
        return toolsChancePercent >= 0 ? toolsChancePercent : rollOnCraftChancePercent;
    }

    /** Материал в чёрном списке (накат запрещён)? */
    public boolean materialBlocked(Material material) {
        return material != null && materialBlacklist.contains(material.name());
    }

    /**
     * Шанс тир-дропа для вида предмета: свой из {@code mob-drop.*-chance-percent}
     * или общий {@code mob-drop.chance-percent} (1.4.87).
     */
    public int mobDropChanceFor(ItemKind kind) {
        if (kind == null) return 0;
        if (kind.isWeapon()) {
            return mobWeaponsChancePercent >= 0 ? mobWeaponsChancePercent : mobDropChancePercent;
        }
        if (kind.isArmor()) {
            return mobArmorChancePercent >= 0 ? mobArmorChancePercent : mobDropChancePercent;
        }
        return mobToolsChancePercent >= 0 ? mobToolsChancePercent : mobDropChancePercent;
    }

    /** Мир в чёрном списке для дропа с мобов (регистр не важен)? */
    public boolean mobWorldBlocked(String worldName) {
        return worldName != null && mobWorldBlacklist.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** Разрешён ли накат для вида предмета согласно флагам craft.*. */
    public boolean kindAllowed(ItemKind kind) {
        if (kind == null) return false;
        if (kind.isWeapon() && craftWeapons) return true;
        if (kind.isArmor() && craftArmor) return true;
        return kind.isTool() && craftTools && !kind.isWeapon();
    }

    /**
     * Шанс вида: -1 — «использовать общий», 0..100 — свой. Прочие значения
     * (в том числе -5 или 150) клампятся с предупреждением в лог (1.4.80).
     */
    static int kindChance(int raw, Logger logger, String key) {
        if (raw < -1 || raw > 100) {
            logger.warning("config: " + key + " вне диапазона -1..100, применена граница");
            return Math.max(-1, Math.min(100, raw));
        }
        return raw;
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона, применена граница");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
