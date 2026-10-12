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

    /** Множитель скорости атаки инструментов (треть процента). */
    public static double attackSpeedScalar(int percent) {
        return percent / 300.0;
    }

    /** Множитель скорости бега (легендарная броня — маленький косметический штрих). */
    public static double movementScalar(int percent) {
        return Math.max(0, percent) / 600.0;
    }

    /** Стоимость опыта апгрейда на наковальне: базовая * (индекс нового тира). */
    public static int upgradeXpCost(int baseXp, int newTierIndex) {
        return Math.max(1, baseXp) * Math.max(1, newTierIndex + 1);
    }
}
