package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.logging.Logger;

/** Снимок конфигурации. */
public record TierForgeConfig(
        int rollOnCraftChancePercent,
        List<TierDef.Snapshot> tiers,
        boolean anvilEnabled,
        int anvilBaseXp,
        boolean craftArmor,
        boolean craftWeapons,
        boolean craftTools) {

    public TierDef.Snapshot find(String id) {
        return tiers.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    public static TierForgeConfig parse(FileConfiguration config, Logger logger) {
        int chance = clamp(config.getInt("roll-on-craft-chance-percent", 100), 0, 100, logger,
                "roll-on-craft-chance-percent");
        List<TierDef.Snapshot> tiers = TierDef.parse(config, logger);
        boolean anvil = config.getBoolean("anvil.enabled", true);
        int baseXp = clamp(config.getInt("anvil.base-xp-cost", 3), 1, 30, logger, "anvil.base-xp-cost");
        return new TierForgeConfig(chance, tiers, anvil, baseXp,
                config.getBoolean("craft.armor", true),
                config.getBoolean("craft.weapons", true),
                config.getBoolean("craft.tools", true));
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
