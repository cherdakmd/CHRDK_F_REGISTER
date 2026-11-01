package ru.cherdakmd.chrdkvk;

/** Купленный ранг меняет только частоту предложений, никогда — обязательство ВК-2FA. */
final class VkOnboardingPolicy {
    static boolean suppress(int activeRank, boolean alreadyShownThisConnection) {
        return activeRank > 0 && alreadyShownThisConnection;
    }
}
