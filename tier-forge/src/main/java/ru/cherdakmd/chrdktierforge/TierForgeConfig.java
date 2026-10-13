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
        boolean identifyUnidentified,
        int identifyScanTicks,
        boolean identifySounds,
        int passBonusCraftPercent,
        int weaponsChancePercent,
        int armorChancePercent,
        int toolsChancePercent,
        java.util.Set<String> materialBlacklist,
        double mobBossMultiplier,
        boolean rerollNoDowngrade) {

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

        java.util.Set<String> blacklist = new java.util.HashSet<>();
        for (String material : config.getStringList("craft.material-blacklist")) {
            if (material != null && !material.isBlank()) {
                blacklist.add(material.trim().toUpperCase(Locale.ROOT));
            }
        }

        return new TierForgeConfig(chance, tiers, anvil, baseXp,
                config.getBoolean("craft.armor", true),
                config.getBoolean("craft.weapons", true),
                config.getBoolean("craft.tools", true),
                reroll, catalyst, rerollXp, mobDrop, mobChance,
                config.getBoolean("identify.enabled", true),
                Math.max(5, config.getInt("identify.scan-ticks", 10)),
                config.getBoolean("identify.sounds", true),
                clamp(config.getInt("identify.pass-bonus-chance-percent", 0), 0, 100, logger,
                        "identify.pass-bonus-chance-percent"),
                config.getInt("craft.weapons-chance-percent", -1),
                config.getInt("craft.armor-chance-percent", -1),
                config.getInt("craft.tools-chance-percent", -1),
                blacklist,
                Math.max(0.0, config.getDouble("mob-drop.boss-multiplier", 2.0)),
                config.getBoolean("reroll.no-downgrade", true));
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

    /** Разрешён ли накат для вида предмета согласно флагам craft.*. */
    public boolean kindAllowed(ItemKind kind) {
        if (kind == null) return false;
        if (kind.isWeapon() && craftWeapons) return true;
        if (kind.isArmor() && craftArmor) return true;
        return kind.isTool() && craftTools && !kind.isWeapon();
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона, применена граница");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
