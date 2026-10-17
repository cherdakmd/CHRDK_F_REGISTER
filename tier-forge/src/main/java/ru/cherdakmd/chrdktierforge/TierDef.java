package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Лестница из 7 тиров крафтовой редкости: от проклятого к легендарному.
 * Порядок лестницы фиксирован в коде; веса/бонусы переопределяются конфигом
 * (веса клампятся в [0..1000], бонусы в [-50..+60]).
 */
public final class TierDef {

    /** Семь тиров в порядке силы. id — международный ключ конфига/PDC. */
    public static final List<String> LADDER = List.of(
            "cursed", "weak", "common", "fine", "rare", "epic", "legendary");

    private static final Map<String, Integer> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("cursed", -12);
        DEFAULTS.put("weak", -5);
        DEFAULTS.put("common", 0);
        DEFAULTS.put("fine", 6);
        DEFAULTS.put("rare", 12);
        DEFAULTS.put("epic", 20);
        DEFAULTS.put("legendary", 30);
    }

    private static final Map<String, Integer> DEFAULT_WEIGHTS = new LinkedHashMap<>();
    static {
        DEFAULT_WEIGHTS.put("cursed", 5);
        DEFAULT_WEIGHTS.put("weak", 15);
        DEFAULT_WEIGHTS.put("common", 30);
        DEFAULT_WEIGHTS.put("fine", 20);
        DEFAULT_WEIGHTS.put("rare", 15);
        DEFAULT_WEIGHTS.put("epic", 10);
        DEFAULT_WEIGHTS.put("legendary", 5);
    }

    public static boolean isTier(String id) {
        return LADDER.contains(id);
    }

    /** Индекс тира в лестнице (0..6), -1 — не тир. */
    public static int indexOf(String id) {
        return LADDER.indexOf(id);
    }

    /** Тир выше, или null на потолке. */
    public static String next(String id) {
        int index = indexOf(id);
        return index < 0 || index == LADDER.size() - 1 ? null : LADDER.get(index + 1);
    }

    /** Снимки свойств тира (read-only). */
    public record Snapshot(String id, int weight, int percent) {
    }

    /** Парсит конфиг в снимки лестницы по порядку (клампы и дефолты с логом). */
    public static List<Snapshot> parse(FileConfiguration config, Logger logger) {
        List<Snapshot> result = new ArrayList<>();
        for (String id : LADDER) {
            ConfigurationSection section = config.getConfigurationSection("tiers." + id);
            int weight = section == null ? DEFAULT_WEIGHTS.get(id)
                    : clamp(section.getInt("weight", DEFAULT_WEIGHTS.get(id)), 0, 1000, logger,
                    "tiers." + id + ".weight");
            int percent = section == null ? DEFAULTS.get(id)
                    : clamp(section.getInt("percent", DEFAULTS.get(id)), -50, 60, logger,
                    "tiers." + id + ".percent");
            result.add(new Snapshot(id, weight, percent));
        }
        return List.copyOf(result);
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона, применена граница");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
