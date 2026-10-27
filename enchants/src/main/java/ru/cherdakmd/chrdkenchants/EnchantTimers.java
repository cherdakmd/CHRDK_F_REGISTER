package ru.cherdakmd.chrdkenchants;

/** Только отображение таймеров; не меняет и не очищает PDC. */
final class EnchantTimers {
    static final String PVE = "pve_ready_at", WORK = "utility_work_ready", AIR = "utility_air_ready";
    static long seconds(long until, long now) {
        if (until <= now) return 0;
        long delta;
        try { delta = Math.subtractExact(until, now); }
        catch (ArithmeticException ignored) { delta = Long.MAX_VALUE; }
        return delta / 1000 + (delta % 1000 == 0 ? 0 : 1);
    }
    private EnchantTimers() { }
}
