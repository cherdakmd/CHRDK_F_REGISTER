package ru.cherdakmd.chrdkregister;

import java.util.Random;

/**
 * Выбор случайной точки для спавна: кольцо {@code [min-radius..radius]} вокруг центра,
 * равномерно по площади ({@code r = min + sqrt(u) * (R - min)}) — иначе точки скапливались
 * бы у центра. Координаты ограничены ванильным лимитом мира (±30 000 000).
 *
 * <p>Чистая математика без Bukkit — покрывается обычными модульными тестами.</p>
 */
public final class SpawnSpotPicker {

    /** Лимит координат ванильного мира. */
    public static final int WORLD_LIMIT = 30_000_000;

    private SpawnSpotPicker() {
    }

    /**
     * Возвращает {@code {x, z}} — блочные координаты кандидата для спавна.
     *
     * @param radius    внешний радиус (0 — ровно в центре)
     * @param minRadius внутренний радиус; больше радиуса — прижимается к нему
     */
    public static int[] pick(Random random, long centerX, long centerZ, int radius, int minRadius) {
        int outer = Math.max(0, radius);
        int inner = Math.min(Math.max(0, minRadius), outer);
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = inner + Math.sqrt(random.nextDouble()) * (outer - inner);
        int x = clamp(centerX + distance * Math.cos(angle));
        int z = clamp(centerZ + distance * Math.sin(angle));
        return new int[]{x, z};
    }

    /** Округление с прижатием к лимиту мира (без переполнения int). */
    public static int clamp(double value) {
        long rounded = Math.round(value);
        if (rounded > WORLD_LIMIT) {
            return WORLD_LIMIT;
        }
        if (rounded < -WORLD_LIMIT) {
            return -WORLD_LIMIT;
        }
        return (int) rounded;
    }
}
