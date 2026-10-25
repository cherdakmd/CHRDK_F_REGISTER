package ru.cherdakmd.chrdkteleports;

import java.util.Random;

/**
 * Выбор случайной точки в кольце [minRadius..radius] вокруг центра — равномерно по ПЛОЩАДИ
 * (r = min + sqrt(u) * (R - min)), иначе точки скапливались бы у центра. Чистая математика.
 */
public final class RandomSpotFinder {

    private RandomSpotFinder() {
    }

    /** Возвращает {x, z} — блочные координаты кандидата. */
    public static long[] pick(Random random, long centerX, long centerZ, long radius, long minRadius) {
        double angle = random.nextDouble() * Math.PI * 2;
        double base = Math.min(minRadius, radius);
        double span = radius - base;
        double distance = base + Math.sqrt(random.nextDouble()) * span;
        long x = Math.round(centerX + distance * Math.cos(angle));
        long z = Math.round(centerZ + distance * Math.sin(angle));
        return new long[]{x, z};
    }
}
