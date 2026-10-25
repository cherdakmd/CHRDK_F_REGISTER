package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Настройки случайного спавна (секция {@code spawn} в config.yml): где искать точку,
 * кого не трогать (кровать/точка возрождения, дом, свой приват) и когда срабатывать.
 *
 * <p>Отсутствующая секция не выключает функцию: применяются значения по умолчанию —
 * так обновление не требует правки существующего config.yml. Недопустимые числа
 * приводятся к границам с записью в лог (как у остальных секций ядра).</p>
 */
public record SpawnSettings(
        boolean enabled,
        boolean onFirstJoin,
        boolean onDeath,
        String world,                 // пусто = мир игрока
        boolean useWorldSpawnCenter,
        int centerX,
        int centerZ,
        int radiusBlocks,
        int minRadiusBlocks,
        int maxAttempts,
        boolean avoidClaims,          // не выбирать точку внутри чужого привата
        boolean respectClaims,        // не рандомить игрока со своим приватом
        boolean respectHomes,         // не рандомить игрока с домом в CHRDK_Teleports
        boolean otherDimensions) {    // смерть в Нижнем мире/Энде → случайная точка основного мира

    public static final int DEFAULT_RADIUS = 3_000;
    public static final int DEFAULT_MIN_RADIUS = 200;
    public static final int DEFAULT_ATTEMPTS = 16;

    /** Значения по умолчанию: используются, если секции spawn в config.yml ещё нет. */
    public static SpawnSettings defaults() {
        return new SpawnSettings(true, true, true, "", true, 0, 0,
                DEFAULT_RADIUS, DEFAULT_MIN_RADIUS, DEFAULT_ATTEMPTS, true, true, true, true);
    }

    public static SpawnSettings parse(FileConfiguration config, Logger logger) {
        ConfigurationSection spawn = config == null ? null : config.getConfigurationSection("spawn");
        if (spawn == null) {
            return defaults();
        }
        int radius = clamp(spawn.getInt("radius-blocks", DEFAULT_RADIUS),
                1, 30_000_000, logger, "spawn.radius-blocks");
        int minRadius = clamp(spawn.getInt("min-radius-blocks", DEFAULT_MIN_RADIUS),
                0, radius, logger, "spawn.min-radius-blocks");
        int attempts = clamp(spawn.getInt("max-attempts", DEFAULT_ATTEMPTS),
                1, 200, logger, "spawn.max-attempts");
        String world = spawn.getString("world", "");
        // other-dimensions: overworld (по умолчанию) — рандом и после смерти в Нижнем/Энде;
        // vanilla — в этих измерениях работает обычное возрождение.
        String otherDimensions = spawn.getString("other-dimensions", "overworld");
        boolean randomOutsideOverworld = otherDimensions == null
                || !"vanilla".equalsIgnoreCase(otherDimensions.trim());
        return new SpawnSettings(
                spawn.getBoolean("enabled", true),
                spawn.getBoolean("on-first-join", true),
                spawn.getBoolean("on-death", true),
                world == null ? "" : world.trim(),
                spawn.getBoolean("use-world-spawn-center", true),
                spawn.getInt("center-x", 0),
                spawn.getInt("center-z", 0),
                radius, minRadius, attempts,
                spawn.getBoolean("avoid-claims", true),
                spawn.getBoolean("respect-claims", true),
                spawn.getBoolean("respect-homes", true),
                randomOutsideOverworld);
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона [" + min + ".." + max
                    + "], применено соседнее значение");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
