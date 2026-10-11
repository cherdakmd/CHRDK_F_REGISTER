package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Снимок конфигурации: источники чар, лимиты, флаги по каждой id. */
public record EnchantsConfig(
        boolean tableEnabled,
        int tableChancePercent,
        boolean villagerEnabled,
        int villagerConvertChancePercent,
        int maxPerItem,
        int maxLegendaryPerItem,
        List<EnchantDef> enabledEnchants) {

    public boolean isEnabled(String id) {
        return enabledEnchants.stream().anyMatch(def -> def.id().equals(id));
    }

    public static EnchantsConfig parse(FileConfiguration config, Logger logger) {
        boolean table = clampBool(config, "sources.table.enabled", true);
        int tableChance = clamp(config.getInt("sources.table.chance-percent", 35), 0, 100,
                logger, "sources.table.chance-percent");
        boolean villager = clampBool(config, "sources.villager.enabled", true);
        int villagerChance = clamp(config.getInt("sources.villager.convert-chance-percent", 50),
                0, 100, logger, "sources.villager.convert-chance-percent");
        int maxPerItem = clamp(config.getInt("rules.max-per-item", 3), 1, 30, logger, "rules.max-per-item");
        int maxLegendary = clamp(config.getInt("rules.max-legendary-per-item", 1), 0, maxPerItem,
                logger, "rules.max-legendary-per-item");
        List<EnchantDef> enabled = new ArrayList<>();
        for (EnchantDef def : EnchantRegistry.all()) {
            if (config.getBoolean("enchants." + def.id() + ".enabled", true)) {
                enabled.add(def);
            }
        }
        return new EnchantsConfig(table, tableChance, villager, villagerChance,
                maxPerItem, maxLegendary, List.copyOf(enabled));
    }

    private static boolean clampBool(FileConfiguration config, String key, boolean fallback) {
        return config.contains(key) ? config.getBoolean(key, fallback) : fallback;
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона [" + min + ".." + max + "], применена граница");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
