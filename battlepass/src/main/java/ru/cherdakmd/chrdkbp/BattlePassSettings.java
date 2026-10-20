package ru.cherdakmd.chrdkbp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки батлпасса (секция {@code battlepass} в config.yml), не относящиеся
 * к самим заданиям.
 *
 * <p>Все значения имеют безопасные значения по умолчанию: старый config.yml без
 * новых ключей продолжает работать, а отсутствующая секция вообще не ломает
 * плагин. Числовые значения зажимаются в разумные границы, чтобы опечатка в
 * конфиге не превратила полосу прогресса в 400 секций или автосохранение — в
 * запись файла каждую секунду.</p>
 */
public record BattlePassSettings(
        boolean countCreative,
        boolean ignorePlayerPlaced,
        int placedMemorySeconds,
        boolean matchVariants,
        int barWidth,
        boolean sounds,
        boolean actionbar,
        boolean announceNewWaves,
        boolean menuEnabled,
        int autosaveSeconds,
        boolean premiumEnabled,
        String premiumProduct) {

    public static final int MIN_BAR_WIDTH = 3;
    public static final int MAX_BAR_WIDTH = 16;
    public static final int MIN_AUTOSAVE_SECONDS = 30;
    public static final int MAX_AUTOSAVE_SECONDS = 3600;
    public static final int MIN_MEMORY_SECONDS = 30;
    public static final int MAX_MEMORY_SECONDS = 7200;

    /** Значения по умолчанию: честная игра включена, всё остальное — удобства. */
    public static BattlePassSettings defaults() {
        return new BattlePassSettings(false, true, 600, true, 8, true, true, true, true, 300,
                true, "battlepass-premium");
    }

    public static BattlePassSettings parse(FileConfiguration config) {
        ConfigurationSection root = config == null
                ? null : config.getConfigurationSection("battlepass");
        if (root == null) {
            return defaults();
        }
        ConfigurationSection fair = root.getConfigurationSection("fair-play");
        ConfigurationSection display = root.getConfigurationSection("display");
        ConfigurationSection premium = root.getConfigurationSection("premium");
        return new BattlePassSettings(
                fair != null && fair.getBoolean("count-creative", false),
                fair == null || fair.getBoolean("ignore-player-placed", true),
                clamp(fair == null ? 600 : fair.getInt("placed-memory-seconds", 600),
                        MIN_MEMORY_SECONDS, MAX_MEMORY_SECONDS),
                root.getBoolean("match-variants", true),
                clamp(display == null ? 8 : display.getInt("bar-width", 8),
                        MIN_BAR_WIDTH, MAX_BAR_WIDTH),
                display == null || display.getBoolean("sounds", true),
                display == null || display.getBoolean("actionbar", true),
                display == null || display.getBoolean("announce-new-waves", true),
                display == null || display.getBoolean("menu", true),
                clamp(root.getInt("autosave-seconds", 300),
                        MIN_AUTOSAVE_SECONDS, MAX_AUTOSAVE_SECONDS),
                premium == null || premium.getBoolean("enabled", true),
                product(premium == null ? "battlepass-premium"
                        : premium.getString("product", "battlepass-premium")));
    }

    /** id товара каталога: только безопасные символы, иначе — значение по умолчанию. */
    private static String product(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[a-z0-9][a-z0-9_-]{0,31}") ? value : "battlepass-premium";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
