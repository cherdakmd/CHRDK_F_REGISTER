package ru.cherdakmd.chrdkhunt;

import java.util.Set;

/** Временное ускорение не заменяет движение/AI. Совокупный множитель CHRDK не выше 1.10. */
final class EliteDash {
    static final int VARIANT = 6;
    private static final Set<String> SUPPORTED = Set.of("ZOMBIE", "HUSK", "DROWNED", "SKELETON", "STRAY",
            "BOGGED", "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "PILLAGER", "VINDICATOR", "WITCH",
            "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN", "ENDERMAN", "SILVERFISH", "ENDERMITE", "RAVAGER");
    record Settings(boolean enabled, int ticks, long cooldown, double ceiling) {
        static Settings bounded(boolean enabled, int ticks, int seconds, double ceiling) {
            return new Settings(enabled, Math.clamp(ticks, 10, 30), Math.clamp(seconds, 10, 60) * 1000L,
                    Double.isFinite(ceiling) ? Math.clamp(ceiling, 1.01, 1.10) : 1.10);
        }
        double boost(double levelFactor) {
            if (!Double.isFinite(levelFactor) || levelFactor < 1 || levelFactor >= ceiling) return 0;
            // MULTIPLY_SCALAR_1 перемножаются: нельзя добавлять ещё +10% поверх уровня.
            double factor = Math.nextDown(ceiling) / levelFactor;
            return factor > 1 ? factor - 1 : 0;
        }
        Long reserve(long next, long now) {
            return enabled && next >= 0 && now >= next && now >= 0 && now <= Long.MAX_VALUE - cooldown ? now + cooldown : null;
        }
    }
    static boolean supported(String type) { return type != null && SUPPORTED.contains(type); }
    static boolean range(double squared) { return Double.isFinite(squared) && squared >= 4 && squared <= 144; }
    static boolean ownedLevel(double amount, double expectedFactor, boolean totalOperation) {
        return totalOperation && Double.isFinite(amount) && Double.isFinite(expectedFactor)
                && expectedFactor >= 1 && expectedFactor <= 1.10 && Math.abs(amount - (expectedFactor - 1)) <= 1e-12;
    }
    private EliteDash() { }
}
