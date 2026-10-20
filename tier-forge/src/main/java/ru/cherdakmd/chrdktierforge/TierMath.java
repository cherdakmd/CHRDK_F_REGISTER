package ru.cherdakmd.chrdktierforge;

/**
 * Формулы бонусов: из одного процента тира выводятся величины атрибутов по kind-у.
 * Чистая математика — юнит-тестируема.
 */
public final class TierMath {

    private TierMath() {
    }

    /**
     * Множитель MULTIPLY_SCALAR_1 для урона (оружие ближнего боя).
     *
     * <p>С 1.4.124 процент тира удваивается: легендарный клинок даёт +60% к
     * итоговому урону, а не +30%. Потолок +100% и штраф не ниже −40% держат
     * кривые админские значения в безопасных рамках.</p>
     */
    public static double damageScalar(int percent) {
        return clamp(percent * 0.02, -0.40, 1.00);
    }

    /**
     * Множитель урона стрел для луков и арбалетов: тот же масштаб, что у
     * ближнего боя. Атрибут ATTACK_DAMAGE на снаряды не влияет, поэтому
     * бонус начисляет {@link ForgeUpgradeListener} по факту попадания.
     */
    public static double projectileScalar(int percent) {
        return damageScalar(percent);
    }

    /** Множитель для защиты (каждый слот брони). */
    public static double defenseScalar(int percent) {
        return percent / 100.0;
    }

    /** Множитель для твёрдости брони (половина базового процента). */
    public static double toughnessScalar(int percent) {
        return percent / 200.0;
    }

    /**
     * Множитель скорости добычи инструментов: процент тира утраивается.
     * Легендарная кирка копает на +90% быстрее, а не на +30% (1.4.124).
     */
    public static double miningScalar(int percent) {
        return clamp(percent * 0.03, -0.40, 1.20);
    }

    /**
     * Множитель скорости бега за одну деталь брони.
     *
     * <p>Раньше легендарная деталь давала +5%, и полный сет едва ощущался.
     * С 1.4.124 — +6% за деталь (легендарный сет ≈ +26% скорости), но не
     * больше +8% за деталь даже при админском проценте выше 40.</p>
     */
    public static double movementScalar(int percent) {
        return clamp(Math.max(0, percent) / 500.0, 0.0, 0.08);
    }

    /** Процент из множителя для лора и {@code /tiers}: 0.6 → 60. */
    public static int percentOf(double scalar) {
        return (int) Math.round(scalar * 100.0);
    }

    /** Процент со знаком: 0.6 → "+60", −0.4 → "-40". */
    public static String signedPercent(double scalar) {
        int value = percentOf(scalar);
        return (value > 0 ? "+" : "") + value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    /** Стоимость опыта апгрейда на наковальне: базовая * (индекс нового тира). */
    public static int upgradeXpCost(int baseXp, int newTierIndex) {
        return Math.max(1, baseXp) * Math.max(1, newTierIndex + 1);
    }

    /**
     * Стоимость опыта переката: базовая * (индекс ТЕКУЩЕГО тира + 1) — чем дороже
     * предмет, тем больше жалко его перекатывать. Индекс 0 (проклятый) даёт минимум.
     */
    public static int rerollXpCost(int baseXp, int currentTierIndex) {
        return Math.max(1, baseXp) * Math.max(1, currentTierIndex + 1);
    }
}
