package ru.cherdakmd.chrdkgenerator;

/** Чистая математика генератора (п.31): циклы, бункер, лимиты выдачи,
 * множитель перерождений. Без состояния и Bukkit — только формулы. */
public final class GeneratorMath {
    private GeneratorMath() {}

    /** Секунды производственного цикла: −8% за уровень модуля «Скорость»,
     * пол — 20% от базы. Минимум одна секунда. */
    public static int cycleSeconds(int baseSeconds, int speedLevel) {
        double factor = Math.max(0.2, 1.0 - 0.08 * Math.max(0, speedLevel));
        return Math.max(1, (int) Math.round(baseSeconds * factor));
    }

    /** Ёмкость бункера: +25% за уровень модуля «Ёмкость», минимум одна единица. */
    public static int bunkerCapacity(int base, int capacityLevel) {
        return Math.max(1, (int) Math.round(base * (1.0 + 0.25 * Math.max(0, capacityLevel))));
    }

    /** Дневной лимит выдачи денег: base + тир×perTier + ступень ранга×perRankStep,
     * зажат в 1…hardMax. Ранг участвует только в этом потолке. */
    public static int payoutDailyCap(int base, int perTier, int tier, int perRankStep, int rankStep, int hardMax) {
        long total = (long) base
                + (long) perTier * Math.max(0, tier)
                + (long) perRankStep * Math.max(0, rankStep);
        return (int) Math.max(1L, Math.min(hardMax, total));
    }

    /** Бонус производства за перерождения, %: min(cap, percent × (1 − decayⁿ) / (1 − decay)).
     * Та же кривая, что у опыта навыков: +5% за перерождение, затухание 0.9, кап +50%. */
    public static double rebirthBonusPercent(double percentPerRebirth, double decay, double capPercent, int rebirths) {
        int n = Math.max(0, rebirths);
        if (percentPerRebirth <= 0 || n == 0) return 0.0;
        double sum = decay >= 1.0
                ? percentPerRebirth * n
                : percentPerRebirth * (1.0 - Math.pow(decay, n)) / (1.0 - decay);
        return sum >= capPercent ? capPercent : sum;
    }
}
