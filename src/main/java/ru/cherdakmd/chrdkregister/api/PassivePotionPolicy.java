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

    public static boolean matches(int amplifier, int duration, int ownedAmplifier,
                                  int ownedDuration, long elapsed) {
        return amplifier == ownedAmplifier && duration > 0
                && Math.abs((long) duration - remaining(ownedDuration, elapsed)) <= 2;
    }
}
