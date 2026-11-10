package ru.cherdakmd.chrdkdisasters;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Геометрия катастроф в смещениях относительно центра. Все методы чистые и
 * детерминированные: один и тот же seed/шаг даёт одну и ту же карту точек,
 * поэтому форму воронки, кольца предупреждения и заливки можно проверить JUnit
 * без Bukkit и без работающего мира.
 */
public final class DisasterGeometry {

    private DisasterGeometry() {
    }

    /** Спираль смерча: с каждым шагом поворачивается, радиус растёт к краю. */
    public static List<int[]> spiral(int radius, int points, int step) {
        int safeRadius = Math.max(1, radius);
        int safePoints = Math.max(1, points);
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < safePoints; i++) {
            double angle = step * 0.45 + i * 0.55;
            double ring = safeRadius * (0.15 + 0.85 * (i + 1) / safePoints);
            int x = (int) Math.round(Math.cos(angle) * ring);
            int z = (int) Math.round(Math.sin(angle) * ring);
            if (x * x + z * z > safeRadius * safeRadius) {
                continue;
            }
            if (seen.add(x + ":" + z)) {
                out.add(new int[]{x, z});
            }
        }
        return List.copyOf(out);
    }

    /** Кольцо предупреждения: точки по окружности радиуса, без изменения мира. */
    public static List<int[]> ring(int radius, int count, int step) {
        int safeRadius = Math.max(1, radius);
        int safeCount = Math.max(1, count);
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < safeCount; i++) {
            double angle = step * 0.25 + i * 2.0 * Math.PI / safeCount;
            int x = (int) Math.round(Math.cos(angle) * safeRadius);
            int z = (int) Math.round(Math.sin(angle) * safeRadius);
            if (seen.add(x + ":" + z)) {
                out.add(new int[]{x, z});
            }
        }
        return List.copyOf(out);
    }

    /** Круглая площадка для заливки водой и точечных попаданий. */
    public static List<int[]> scatter(int radius, int count, long seed) {
        int safeRadius = Math.max(1, radius);
        int safeCount = Math.max(0, count);
        Random random = new Random(seed);
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < safeCount && guard++ < safeCount * 12 + 64) {
            int x = random.nextInt(safeRadius * 2 + 1) - safeRadius;
            int z = random.nextInt(safeRadius * 2 + 1) - safeRadius;
            if (x * x + z * z > safeRadius * safeRadius) {
                continue;
            }
            if (seen.add(x + ":" + z)) {
                out.add(new int[]{x, z});
            }
        }
        return List.copyOf(out);
    }

    /** Плотность частиц фазы: затухание заметно слабее активного тика. */
    public static int particles(int level, boolean fading) {
        int base = 20 + Math.max(1, level) * 8;
        return fading ? Math.max(4, base / 4) : base;
    }
}
