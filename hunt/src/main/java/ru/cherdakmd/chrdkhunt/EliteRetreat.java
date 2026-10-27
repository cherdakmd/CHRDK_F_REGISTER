package ru.cherdakmd.chrdkhunt;

import java.util.List;
import java.util.Set;

/** Короткое наземное отступление: тот же чанк/высота, без бонуса скорости и гарантированного спасения. */
final class EliteRetreat {
    static final int VARIANT = 8, ACTIVE_LIMIT = 16, PLAN_LIMIT = 4, SEARCH_LIMIT = 8, MAX_POINTS = 8;
    private static final Set<String> TYPES = Set.of("ZOMBIE", "HUSK", "SKELETON", "STRAY", "BOGGED",
            "WITHER_SKELETON", "PILLAGER", "VINDICATOR", "WITCH", "ZOMBIFIED_PIGLIN");
    private static final Set<String> HAZARDS = Set.of("MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE",
            "POWDER_SNOW", "SWEET_BERRY_BUSH", "WITHER_ROSE", "FIRE", "SOUL_FIRE", "LAVA", "WATER");
    record Point(double x, double z) { }
    record Settings(boolean enabled, int ticks, long cooldown, int attempts, double threshold, double distance) {
        static Settings bounded(boolean enabled, int ticks, int seconds, int attempts, double percent, double distance) {
            return new Settings(enabled, Math.clamp(ticks, 20, 40), Math.clamp(seconds, 10, 60) * 1000L,
                    Math.clamp(attempts, 1, 2), (Double.isFinite(percent) ? Math.clamp(percent, 15, 30) : 25) / 100,
                    Double.isFinite(distance) ? Math.clamp(distance, 3, 5) : 4);
        }
        boolean low(double hp, double maximum) {
            return enabled && Double.isFinite(hp) && Double.isFinite(maximum) && hp > 0 && maximum > 0 && hp <= maximum * threshold;
        }
    }
    record Stamp(int used, long next) {
        static Stamp decode(long[] data) {
            if (data == null) return new Stamp(0, 0);
            return data.length == 2 && data[0] >= 0 && data[0] <= 2 && data[1] >= 0 ? new Stamp((int) data[0], data[1]) : null;
        }
        boolean ready(Settings settings, long now) {
            return settings.enabled() && used >= 0 && used < settings.attempts() && next >= 0 && now >= 0 && now >= next;
        }
        Stamp spend(Settings settings, long now) {
            return ready(settings, now) && now <= Long.MAX_VALUE - settings.cooldown() ? new Stamp(used + 1, now + settings.cooldown()) : null;
        }
        long[] encode() { return new long[]{used, next}; }
    }
    static boolean supported(String type) { return type != null && TYPES.contains(type); }
    static boolean cell(int x, int y, int z, int originX, int originY, int originZ) {
        return y == originY && (x >> 4) == (originX >> 4) && (z >> 4) == (originZ >> 4)
                && Math.abs((long) x - originX) <= 6 && Math.abs((long) z - originZ) <= 6;
    }
    static boolean floor(String type, boolean occluding) {
        return occluding && type != null && !HAZARDS.contains(type);
    }
    static List<Point> candidates(double x, double z, double playerX, double playerZ, double distance) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(playerX) || !Double.isFinite(playerZ)
                || !Double.isFinite(distance) || distance < 3 || distance > 5) return List.of();
        double dx = x - playerX, dz = z - playerZ, length = Math.hypot(dx, dz);
        if (!Double.isFinite(length) || length < .01) return List.of();
        dx /= length; dz /= length;
        double diagonal = Math.sqrt(.5), sideX = (dx - dz) * diagonal, sideZ = (dx + dz) * diagonal;
        return List.of(new Point(x + dx * distance, z + dz * distance),
                new Point(x + sideX * distance, z + sideZ * distance),
                new Point(x + (dx + dz) * diagonal * distance, z + (dz - dx) * diagonal * distance));
    }
    private EliteRetreat() { }
}
