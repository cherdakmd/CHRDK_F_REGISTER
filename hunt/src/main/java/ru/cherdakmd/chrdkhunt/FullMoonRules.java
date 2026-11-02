package ru.cherdakmd.chrdkhunt;

/** Чистые границы нагрузки и календаря. Ни один конфиг не снимает жёсткие потолки. */
final class FullMoonRules {
    record Settings(boolean enabled, double chance, int globalCap, int perPlayer, int nearbyCap,
                    int attempts, int spawnBudget, int cleanupBudget, int radiusMin, int radiusMax,
                    int spawnEvery, int maxSeconds, int spawnProtection, double msptLimit) {
        static Settings bounded(boolean enabled, double chance, int global, int personal, int nearby,
                                int attempts, int budget, int cleanup, int min, int max, int every,
                                int seconds, int protection, double mspt) {
            int g = Math.clamp(global, 1, 128), p = Math.clamp(personal, 1, Math.min(12, g));
            int inner = Math.clamp(min, 20, 40);
            return new Settings(enabled, Double.isFinite(chance) ? Math.clamp(chance, 0, 50) : 25,
                    g, p, Math.clamp(nearby, p, Math.min(24, g)), Math.clamp(attempts, 1, 8),
                    Math.clamp(budget, 1, 4), Math.clamp(cleanup, 1, 16), inner,
                    Math.clamp(max, inner + 4, 64), Math.clamp(every, 5, 60), Math.clamp(seconds, 60, 900),
                    Math.clamp(protection, 64, 4096), Double.isFinite(mspt) ? Math.clamp(mspt, 20, 45) : 40);
        }
    }
    static final long WARNING = 12_000, NIGHT = 13_000, DAWN = 23_000;
    static long day(long fullTime) { return Math.floorDiv(fullTime, 24_000); }
    static long phase(long fullTime) { return Math.floorMod(fullTime, 24_000); }
    static boolean fullMoon(long day) { return day >= 0 && day % 8 == 0; }
    static boolean warning(long phase) { return phase >= WARNING && phase < NIGHT; }
    static boolean night(long phase) { return phase >= NIGHT && phase < DAWN; }
    static boolean blocksSleep(long phase) { return phase >= WARNING && phase < DAWN; }
    static boolean expired(long started, long now, int maxSeconds) {
        return started > 0 && (now < started || now - started >= maxSeconds * 1000L);
    }
    static boolean room(int total, int personal, int nearby, Settings s) {
        return total < s.globalCap() && personal < s.perPlayer() && nearby < s.nearbyCap();
    }
    private FullMoonRules() { }
}
