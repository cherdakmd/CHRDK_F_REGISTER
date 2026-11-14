package ru.cherdakmd.chrdkmobs;

import java.util.List;

/**
 * Тиры мобов: чистая математика без Bukkit.
 *
 * <p>Тир определяется горизонтальным расстоянием от спавна мира: список
 * границ (в блоках) делит мир на тиры 1..N. Тир 1 — базовые мобы без
 * надбавок; каждый следующий тир усиливает здоровье и урон и увеличивает
 * награду за убийство. Все величины считаются из параметров конфига —
 * баланс правится без миграции данных.</p>
 */
public final class MobTierMath {

    /** Разумный потолок числа границ тиров. */
    public static final int MAX_THRESHOLDS = 20;

    private MobTierMath() { }

    /**
     * Тир по расстоянию: 1 — до первой границы, дальше — по числу пройденных
     * границ. Пустой или отсутствующий список — всегда тир 1.
     */
    public static int tierFor(double distance, List<Integer> thresholds) {
        if (thresholds == null || thresholds.isEmpty() || !(distance >= 0.0)) {
            return 1;
        }
        int tier = 1;
        for (int threshold : thresholds) {
            if (distance >= (double) threshold) {
                tier++;
            }
        }
        return tier;
    }

    /** Множитель тира: 1 + perTier × (тир − 1); perTier зажимается в 0..10. */
    public static double factor(int tier, double perTier) {
        int safeTier = Math.max(1, tier);
        double safeStep = Math.min(10.0, Math.max(0.0, perTier));
        return 1.0 + safeStep * (safeTier - 1);
    }

    /** Множитель здоровья тира. */
    public static double healthFactor(int tier, double perTier) {
        return factor(tier, perTier);
    }

    /** Множитель урона тира. */
    public static double damageFactor(int tier, double perTier) {
        return factor(tier, perTier);
    }

    /** Награда за убийство в алмазах: rewardPerTier за каждый тир выше первого. */
    public static long rewardDiamonds(int tier, long rewardPerTier) {
        int safeTier = Math.max(1, tier);
        long safeStep = Math.max(0L, rewardPerTier);
        return (long) (safeTier - 1) * safeStep;
    }

    /** Очки прогресса игрока: суммарный уровень навыков + престиж × вес. */
    public static int progressMetric(int totalLevel, int prestige, int prestigeWeight) {
        int safeLevel = Math.max(0, totalLevel);
        int safePrestige = Math.max(0, prestige);
        int safeWeight = Math.max(0, prestigeWeight);
        return safeLevel + safePrestige * safeWeight;
    }

    /**
     * Множитель прогресса: 1 + min(maxBonus, per100 × метрика / 100).
     * Шаг надбавки зажимается в 0..10, потолок — в 0..10.
     */
    public static double progressFactor(int metric, double per100, double maxBonus) {
        int safeMetric = Math.max(0, metric);
        double safeStep = Math.min(10.0, Math.max(0.0, per100));
        double safeCap = Math.min(10.0, Math.max(0.0, maxBonus));
        return 1.0 + Math.min(safeCap, safeStep * safeMetric / 100.0);
    }

    /**
     * Награда за элитного моба: как за тир выше на {@code rewardTiers}
     * ступеней (элита тира 1 платит тоже).
     */
    public static long eliteRewardDiamonds(int tier, long rewardPerTier, int rewardTiers) {
        return rewardDiamonds(Math.max(1, tier) + Math.max(0, rewardTiers), rewardPerTier);
    }
}
