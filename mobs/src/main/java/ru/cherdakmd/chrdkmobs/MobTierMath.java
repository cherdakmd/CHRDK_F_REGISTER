package ru.cherdakmd.chrdkmobs;

import java.util.List;

/** Тиры мобов: чистая математика без Bukkit.
 *
 * <p>Тир определяется горизонтальным расстоянием от спавна мира: список
 * границ (в блоках) делит мир на тиры 1..N. Свойства тиров (здоровье, урон,
 * способности, награда, лут) живут в таблице конфига {@code tiers.table} —
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
     * Потолок суммарного снижения входящего урона плагинами: с тира 6 потолок
     * падает на {@code perTierStep} за тир — высшие тиры пробивают больше
     * защиты, — но не ниже {@code floor}.
     */
    public static double mitigationCap(int tier, double cap, double perTierStep, double floor) {
        double safeCap = unit(cap);
        double safeStep = unit(perTierStep);
        double safeFloor = Math.min(safeCap, unit(floor));
        return Math.max(safeFloor, safeCap - safeStep * Math.max(0, Math.max(1, tier) - 5));
    }

    /** Доли 0..1; нечисловое значение считается нулём. */
    private static double unit(double value) {
        return Double.isFinite(value) ? Math.min(1.0, Math.max(0.0, value)) : 0.0;
    }
}
