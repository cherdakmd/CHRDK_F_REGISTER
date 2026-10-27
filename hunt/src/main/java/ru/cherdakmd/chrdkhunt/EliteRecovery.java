package ru.cherdakmd.chrdkhunt;

/** Чистый баланс восстанавливающейся элиты. HP, не сердца; бюджет хранится в PDC моба. */
final class EliteRecovery {
    static final int VARIANT = 5;
    record Settings(boolean enabled, int delayTicks, long cooldownMillis, int attempts, double hp, double fraction) {
        static Settings bounded(boolean enabled, int delaySeconds, int cooldownSeconds, int attempts, double hp, double percent) {
            return new Settings(enabled, Math.clamp(delaySeconds, 1, 5) * 20,
                    Math.clamp(cooldownSeconds, 10, 60) * 1000L, Math.clamp(attempts, 1, 3),
                    Double.isFinite(hp) ? Math.clamp(hp, .5, 3) : 3,
                    (Double.isFinite(percent) ? Math.clamp(percent, 1, 8) : 8) / 100);
        }
        double amount(double current, double maximum) {
            if (!Double.isFinite(current) || !Double.isFinite(maximum) || current <= 0 || maximum <= current) return 0;
            return Math.min(maximum - current, Math.min(hp, maximum * fraction));
        }
    }
    record Stamp(int used, long next) {
        static Stamp decode(long[] data) {
            if (data == null) return new Stamp(0, 0); // Только отсутствующий ключ — новый бюджет.
            return data.length == 2 && data[0] >= 0 && data[0] <= 3 && data[1] >= 0
                    ? new Stamp((int) data[0], data[1]) : null;
        }
        boolean ready(Settings settings, long now) {
            return settings.enabled && now >= 0 && now >= next && used < settings.attempts;
        }
        Stamp spend(Settings settings, long now) {
            if (!ready(settings, now) || now > Long.MAX_VALUE - settings.cooldownMillis) return null;
            return new Stamp(used + 1, now + settings.cooldownMillis);
        }
        long[] encode() { return new long[]{used, next}; }
    }
    static boolean lostHealth(double before, double after, double damage, boolean cancelled) {
        return !cancelled && Double.isFinite(damage) && damage > 0
                && Double.isFinite(before) && Double.isFinite(after) && after > 0 && before > after;
    }
    private EliteRecovery() { }
}
