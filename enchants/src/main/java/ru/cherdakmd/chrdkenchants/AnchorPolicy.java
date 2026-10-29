package ru.cherdakmd.chrdkenchants;

/** Только остаточная горизонтальная составляющая native-knockback, не иммунитет. */
final class AnchorPolicy {
    static double factor(int level) { return 1 - .15 * Math.clamp(level, 1, 2); }
    static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && (x != 0 || z != 0);
    }
    private AnchorPolicy() { }
}
