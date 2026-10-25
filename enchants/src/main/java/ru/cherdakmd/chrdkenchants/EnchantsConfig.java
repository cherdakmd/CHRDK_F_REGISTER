package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Снимок конфигурации: источники чар, лимиты, проклятия, флаги по каждой id.
 *
 * <p>1.4.128: {@code sources.table.curse-chance-percent} — с какой вероятностью
 * бросок со стола вместо светлой чары выдаёт проклятие (по умолчанию 25% от уже
 * сработавшего шанса), {@code rules.curses-enabled} — мастер-выключатель
 * проклятий: при {@code false} они не выпадают ни из одного источника, но уже
 * лежащие на предметах чары не снимаются.</p>
 */
public record EnchantsConfig(
        boolean tableEnabled,
        int tableChancePercent,
        int tableCurseChancePercent,
        boolean villagerEnabled,
        int villagerConvertChancePercent,
        int maxPerItem,
        int maxLegendaryPerItem,
        boolean cursesEnabled,
        List<EnchantDef> enabledEnchants) {

    public boolean isEnabled(String id) {
        return enabledEnchants.stream().anyMatch(def -> def.id().equals(id));
    }

    /** Включённые проклятия. */
    public List<EnchantDef> enabledCurses() {
        return enabledEnchants.stream().filter(EnchantDef::curse).toList();
    }

    /** Включённые светлые чары. */
    public List<EnchantDef> enabledLight() {
        return enabledEnchants.stream().filter(def -> !def.curse()).toList();
    }

    public static EnchantsConfig parse(FileConfiguration config, Logger logger) {
        boolean table = clampBool(config, "sources.table.enabled", true);
        int tableChance = clamp(config.getInt("sources.table.chance-percent", 35), 0, 100,
                logger, "sources.table.chance-percent");
        int curseChance = clamp(config.getInt("sources.table.curse-chance-percent", 25), 0, 100,
                logger, "sources.table.curse-chance-percent");
        boolean villager = clampBool(config, "sources.villager.enabled", true);
        int villagerChance = clamp(config.getInt("sources.villager.convert-chance-percent", 50),
                0, 100, logger, "sources.villager.convert-chance-percent");
        int maxPerItem = clamp(config.getInt("rules.max-per-item", 3), 1, 30, logger, "rules.max-per-item");
        int maxLegendary = clamp(config.getInt("rules.max-legendary-per-item", 1), 0, maxPerItem,
                logger, "rules.max-legendary-per-item");
        boolean curses = clampBool(config, "rules.curses-enabled", true);
        List<EnchantDef> enabled = new ArrayList<>();
        for (EnchantDef def : EnchantRegistry.all()) {
            if (def.curse() && !curses) {
                continue; // мастер-выключатель сильнее индивидуальных флагов
            }
            if (config.getBoolean("enchants." + def.id() + ".enabled", true)) {
                enabled.add(def);
            }
        }
        return new EnchantsConfig(table, tableChance, curseChance, villager, villagerChance,
                maxPerItem, maxLegendary, curses, List.copyOf(enabled));
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
