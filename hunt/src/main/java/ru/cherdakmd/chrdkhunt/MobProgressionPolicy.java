package ru.cherdakmd.chrdkhunt;

/** Чистый баланс: место задаёт диапазон, суммарный уровень Skills — положение внутри. */
final class MobProgressionPolicy {
    static final int MAX_LEVEL = 30;
    record Stats(double health, double damage, double speed) { }
    record Range(int min, int max) { }
    static Range range(String environment, double distance) {
        if (!Double.isFinite(distance)) distance = 0;
        int band = (int) Math.min(10, Math.max(0, distance) / 1024);
        int base = "NETHER".equals(environment) ? 6 : "THE_END".equals(environment) ? 12 : 1;
        int min = Math.min(MAX_LEVEL, base + band * 2);
        int max = Math.min(MAX_LEVEL, min + 4);
        return new Range(min, max);
    }
    static int level(String environment, double distance, int skillTotal) {
        Range range = range(environment, distance);
        int personal = 1 + Math.max(0, Math.min(1000, skillTotal) - 10) / 40;
        return Math.max(range.min, Math.min(range.max, personal));
    }
    static Stats stats(int level, int elite) {
        int safe = Math.max(1, Math.min(MAX_LEVEL, level));
        return new Stats(Math.min(2.5, (1 + (safe - 1) * .035) * (elite == 1 ? 1.25 : 1)),
                Math.min(1.5, (1 + (safe - 1) * .012) * (elite == 2 ? 1.10 : 1)),
                Math.min(1.10, (1 + (safe - 1) * .002) * (elite == 3 ? 1.03 : 1)));
    }
    static boolean natural(String reason) { return "NATURAL".equals(reason) || "CHUNK_GEN".equals(reason) || "PATROL".equals(reason); }
    static int bookTier(int level) { return level >= 20 ? 3 : level >= 10 ? 2 : 1; }
    static boolean valid(int level, int elite) {
        return level >= 1 && level <= MAX_LEVEL && elite >= 0
                && elite <= ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE;
    }
    static boolean nearer(double distance, java.util.UUID id, double best, java.util.UUID incumbent) {
        return id != null && Double.isFinite(distance) && distance >= 0 && distance <= 128 * 128
                && (distance < best || distance == best && (incumbent == null || id.compareTo(incumbent) < 0));
    }
    static double scaledHealth(double current, double before, double after) {
        if (!Double.isFinite(current) || !Double.isFinite(before) || !Double.isFinite(after)
                || current <= 0 || before <= 0 || after <= 0) return Double.NaN;
        return Math.min(after, Math.max(Math.min(.01, current), after * Math.clamp(current / before, 0, 1)));
    }
    static double armorBonus(double existing, int elite) {
        return elite == 4 && Double.isFinite(existing) && existing >= 0 ? Math.clamp(12 - existing, 0, 4) : 0;
    }
    static double shotFactor(double factor, long launched, long now) {
        if (!Double.isFinite(factor) || factor < 1 || factor > 1.5 || launched < 0 || now < launched
                || now - launched > 30_000) return 1;
        return factor;
    }
    private MobProgressionPolicy() { }
}
