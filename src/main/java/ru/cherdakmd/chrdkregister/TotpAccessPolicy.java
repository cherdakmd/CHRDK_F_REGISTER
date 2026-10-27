package ru.cherdakmd.chrdkregister;

/** Добровольная привязка не равна обходу уже включённого второго фактора. */
final class TotpAccessPolicy {
    static boolean enrollment(boolean enabled, boolean required, boolean enrolled) {
        return enabled && required && !enrolled;
    }
    static boolean sessionNeedsSecondStep(boolean enabled, boolean required, boolean enrolled, boolean sessionSkips) {
        return enrolled && enabled ? required || !sessionSkips : enrollment(enabled, required, enrolled);
    }
    static boolean releaseEnrollment(boolean wasRequired, boolean required, boolean enrolled,
                                     boolean passwordConfirmed, boolean waitingForEnrollment) {
        return wasRequired && !required && !enrolled && passwordConfirmed && waitingForEnrollment;
    }
    private TotpAccessPolicy() { }
}
