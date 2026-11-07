package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки случайного стартового комплекта (секция {@code starter-kit} в
 * config.yml ядра). Как у {@link GuideBookSettings}: отсутствие секции не
 * выключает функцию — применяются значения по умолчанию, поэтому обновление
 * не требует правки владельческого config.yml и не затирает его комментарии.
 *
 * <p>Все числа зажаты в безопасных границах: опечатка в YAML не должна
 * превратить стартовый подарок в раздачу алмазных блоков.</p>
 */
public record StarterKitSettings(
        boolean enabled,
        long bankDiamonds,
        int inventoryDiamonds,
        int armorPieces,
        int tools,
        int artifacts,
        boolean includeCursedArtifacts) {

    /** Стоковые значения: 1000 на /bal, 32 физических, 4 брони, 3 инструмента, 2 артефакта. */
    public static StarterKitSettings defaults() {
        return new StarterKitSettings(true, 1000L, 32, 4, 3, 2, true);
    }

    public static StarterKitSettings parse(FileConfiguration config) {
        ConfigurationSection section = config == null ? null : config.getConfigurationSection("starter-kit");
        if (section == null) {
            return defaults();
        }
        StarterKitSettings d = defaults();
        return new StarterKitSettings(
                section.getBoolean("enabled", d.enabled()),
                clampLong(section.getLong("bank-diamonds", d.bankDiamonds()), 0L, 100_000L),
                clampInt(section.getInt("inventory-diamonds", d.inventoryDiamonds()), 0, 64),
                clampInt(section.getInt("armor-pieces", d.armorPieces()), 0, 4),
                clampInt(section.getInt("tools", d.tools()), 0, 4),
                clampInt(section.getInt("artifacts", d.artifacts()), 0, 6),
                section.getBoolean("include-cursed-artifacts", d.includeCursedArtifacts()));
    }

    private static int clampInt(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static long clampLong(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }
}
