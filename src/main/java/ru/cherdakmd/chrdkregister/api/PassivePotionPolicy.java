package ru.cherdakmd.chrdkregister.api;

/** Арифметика непрерывных пассивов без серверного реестра зелий. */
public final class PassivePotionPolicy {
    public static final int DURATION = 600;
    public static final int REFRESH_AT = 300;

    private PassivePotionPolicy() { }

    public static int remaining(int duration, long elapsed) {
        if (duration == -1) return -1;
        return (int) Math.max(0L, duration - Math.max(0L, elapsed));
    }

    public static boolean protectsExternal(int amplifier, int duration, int wanted) {
        return amplifier > wanted || duration == -1
                || (amplifier == wanted && duration >= DURATION);
    }

    public static boolean preferIncoming(int oldAmplifier, int oldDuration, int amplifier, int duration) {
        if (oldDuration == 0) return true;
        if (oldAmplifier != amplifier) return amplifier > oldAmplifier;
        return oldDuration != -1 && (duration == -1 || duration > oldDuration);
    }

    /** Продление не должно сдвигать фазу периодического лечения регенерацией. */
    public static int renewalDuration(int remaining, int interval) {
        if (interval <= 1) return DURATION;
        int phase = Math.floorMod(remaining, interval);
        return DURATION + Math.floorMod(phase - DURATION, interval);
    }

    public record Snapshot(int amplifier, int duration, boolean ambient, boolean particles, boolean icon) { }
    public record ReleasePlan(boolean ownedFound, java.util.List<Snapshot> restore) { }

    /** Убираем свой видимый/скрытый слой, сохраняя конечные остатки чужих слоёв. */
    public static ReleasePlan releasePlan(java.util.List<Snapshot> chain, Snapshot owned, long elapsed,
                                          java.util.List<Snapshot> fallback) {
        if (owned == null || chain.size() > 64 || fallback.size() > 64) return new ReleasePlan(false, java.util.List.of());
        boolean found = false;
        java.util.Map<Integer, Snapshot> keep = new java.util.TreeMap<>();
        for (Snapshot effect : chain) {
            boolean ours = effect.ambient() == owned.ambient() && effect.particles() == owned.particles()
                    && effect.icon() == owned.icon() && matches(effect.amplifier(), effect.duration(),
                    owned.amplifier(), owned.duration(), elapsed);
            if (ours) found = true;
            else retain(keep, effect);
        }
        if (!found) return new ReleasePlan(false, java.util.List.of());
        for (Snapshot effect : fallback) retain(keep, effect);
        // Слабый эффект первым: vanilla сама складывает чужую скрытую цепочку.
        return new ReleasePlan(true, java.util.List.copyOf(keep.values()));
    }

    private static void retain(java.util.Map<Integer, Snapshot> keep, Snapshot effect) {
        if (effect.duration() != -1 && effect.duration() <= 0) return;
        keep.merge(effect.amplifier(), effect, (a, b) -> a.duration() == -1 ? a
                : b.duration() == -1 || b.duration() > a.duration() ? b : a);
    }

    public static boolean matches(int amplifier, int duration, int ownedAmplifier,
                                  int ownedDuration, long elapsed) {
        return amplifier == ownedAmplifier && duration > 0
                && Math.abs((long) duration - remaining(ownedDuration, elapsed)) <= 2;
    }
}
