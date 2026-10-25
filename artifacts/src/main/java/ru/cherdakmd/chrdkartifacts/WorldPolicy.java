package ru.cherdakmd.chrdkartifacts;

import java.util.Set;

/**
 * Разрешение на «мировые» эффекты проклятых артефактов: «Раковина тоски»
 * нагоняет грозу, «Расколотая Луна» и «Часы дурошлёпа» переводят время. На
 * публичном сервере это инструмент гриферства — один носитель меняет погоду и
 * ночь всему миру. Политика вынесена в конфиг:
 *
 * <pre>
 * world-effects:
 *   weather: true
 *   time: true
 *   worlds: []      # пусто — все миры
 * </pre>
 *
 * <p>Класс чистый — тестируется без Bukkit.</p>
 */
public record WorldPolicy(boolean weather, boolean time, Set<String> worlds) {

    public WorldPolicy {
        worlds = worlds == null ? Set.of() : Set.copyOf(worlds);
    }

    /** Политика по умолчанию: как до 1.4.104 — мировые эффекты разрешены. */
    public static WorldPolicy defaults() {
        return new WorldPolicy(true, true, Set.of());
    }

    /** Разрешена ли смена погоды в этом мире. */
    public boolean allowsWeather(String worldName) {
        return weather && inScope(worldName);
    }

    /** Разрешена ли смена времени в этом мире. */
    public boolean allowsTime(String worldName) {
        return time && inScope(worldName);
    }

    private boolean inScope(String worldName) {
        if (worlds.isEmpty()) {
            return true;
        }
        if (worldName == null) {
            return false;
        }
        // имена миров регистронезависимы: world / World в конфиге — одно и то же
        return worlds.stream().anyMatch(name -> name.equalsIgnoreCase(worldName));
    }
}
