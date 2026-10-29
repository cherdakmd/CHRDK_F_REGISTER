package ru.cherdakmd.chrdkhunt;

import java.util.UUID;

/** Однократная адаптация к повтору типа PvE-атаки; без иммунитетов и постоянной брони. */
final class EliteTactics {
    static final int VARIANT = 7;
    enum Kind { MELEE, PROJECTILE }
    static Kind kind(String cause, boolean directPlayer, boolean playerProjectile) {
        if (directPlayer && ("ENTITY_ATTACK".equals(cause) || "ENTITY_SWEEP_ATTACK".equals(cause))) return Kind.MELEE;
        return playerProjectile && "PROJECTILE".equals(cause) ? Kind.PROJECTILE : null;
    }
    static boolean matches(Kind prepared, Kind incoming, UUID owner, UUID attacker) {
        return prepared != null && prepared == incoming && owner != null && owner.equals(attacker);
    }
    record Settings(boolean enabled, int ticks, long cooldown, int attempts, double fraction) {
        static Settings bounded(boolean enabled, int ticks, int seconds, int attempts, double percent) {
            return new Settings(enabled, Math.clamp(ticks, 40, 100), Math.clamp(seconds, 10, 60) * 1000L,
                    Math.clamp(attempts, 1, 3), (Double.isFinite(percent) ? Math.clamp(percent, 5, 15) : 10) / 100);
        }
        double reduced(double raw) {
            if (!enabled || !Double.isFinite(raw) || raw <= 0) return Double.NaN;
            double result = raw * (1 - fraction);
            // Субнормальный урон не округляется в иммунитет/неизменённый результат.
            return Double.isFinite(result) && result > 0 && result < raw ? result : Double.NaN;
        }
    }
    record Stamp(int used, long next) {
        static Stamp decode(long[] data) {
            if (data == null) return new Stamp(0, 0);
            return data.length == 2 && data[0] >= 0 && data[0] <= 3 && data[1] >= 0 ? new Stamp((int) data[0], data[1]) : null;
        }
        boolean ready(Settings settings, long now) {
            return settings.enabled() && used >= 0 && used < settings.attempts() && next >= 0 && now >= 0 && now >= next;
        }
        Stamp spend(Settings settings, long now) {
            return ready(settings, now) && now <= Long.MAX_VALUE - settings.cooldown() ? new Stamp(used + 1, now + settings.cooldown()) : null;
        }
        long[] encode() { return new long[]{used, next}; }
    }
    private EliteTactics() { }
}
