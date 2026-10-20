package ru.cherdakmd.chrdktierforge;

/**
 * Формулы бонусов: из одного процента тира выводятся величины атрибутов по kind-у.
 * Чистая математика — юнит-тестируема.
 */
public final class TierMath {

    private TierMath() {
    }

    /** Множитель MULTIPLY_SCALAR_1 для урона (оружие и рукояти инструментов). */
    public static double damageScalar(int percent) {
        return percent / 100.0;
    }

    /** Множитель для защиты (каждый слот брони). */
    public static double defenseScalar(int percent) {
        return percent / 100.0;
    }

    /** Множитель для твёрдости брони (половина базового процента). */
    public static double toughnessScalar(int percent) {
        return percent / 200.0;
    }

    /** Множитель скорости добычи инструментов (1.4.117: вместо скорости атаки). */
    public static double miningScalar(int percent) {
        return percent / 100.0;
    }

    /** Множитель скорости бега (легендарная броня — маленький косметический штрих). */
    public static double movementScalar(int percent) {
        return Math.max(0, percent) / 600.0;
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
