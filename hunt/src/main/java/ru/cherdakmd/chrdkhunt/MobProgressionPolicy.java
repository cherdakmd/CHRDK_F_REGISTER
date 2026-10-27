package ru.cherdakmd.chrdkhunt;

/** Чистый баланс: место задаёт диапазон, суммарный уровень Skills — положение внутри. */
final class MobProgressionPolicy {
    static final int MAX_LEVEL = 30;
    record Stats(double health, double damage, double speed) { }
    static int level(String environment, double distance, int skillTotal) {
        if (!Double.isFinite(distance)) distance = 0;
        int band = (int) Math.min(10, Math.max(0, distance) / 1024);
        int base = "NETHER".equals(environment) ? 6 : "THE_END".equals(environment) ? 12 : 1;
        int min = Math.min(MAX_LEVEL, base + band * 2);
        int max = Math.min(MAX_LEVEL, min + 4);
        int personal = 1 + Math.max(0, Math.min(1000, skillTotal) - 10) / 40;
        return Math.max(min, Math.min(max, personal));
    }
    static Stats stats(int level, int elite) {
        int safe = Math.max(1, Math.min(MAX_LEVEL, level));
        return new Stats(Math.min(2.5, (1 + (safe - 1) * .035) * (elite == 1 ? 1.25 : 1)),
                Math.min(1.5, (1 + (safe - 1) * .012) * (elite == 2 ? 1.10 : 1)),
                Math.min(1.10, (1 + (safe - 1) * .002) * (elite == 3 ? 1.03 : 1)));
    }
    static boolean natural(String reason) { return "NATURAL".equals(reason) || "CHUNK_GEN".equals(reason) || "PATROL".equals(reason); }
    static int bookTier(int level) { return level >= 20 ? 3 : level >= 10 ? 2 : 1; }
    private MobProgressionPolicy() { }
}
