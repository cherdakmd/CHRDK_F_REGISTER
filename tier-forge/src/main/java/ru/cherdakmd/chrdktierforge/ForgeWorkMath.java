package ru.cherdakmd.chrdktierforge;

import java.util.function.DoubleSupplier;

/** Чистые правила рабочих эффектов: прежние потолки и ограниченная износом работа. */
final class ForgeWorkMath {
    private ForgeWorkMath() { }

    static int boostedXp(int amount, double bonus) {
        if (amount <= 0 || !Double.isFinite(bonus) || bonus <= 0) return amount;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(amount * (1.0 + bonus)));
    }

    static int columnSides(double strength) {
        if (!Double.isFinite(strength) || strength < 1) return 0;
        return strength >= 2 ? 2 : 1;
    }

    static double magnetRadius(double bonus) {
        return Math.min(5.5, 1.5 + Math.max(0, bonus));
    }

    /** Дубль руды — отдельная копия исходного ресурса, не новый множитель глубины. */
    static int dropTotal(int base, boolean ore, boolean crop, boolean log, boolean deep) {
        int multiplier = (crop ? 2 : 1) * (log ? 2 : 1) * (deep ? 2 : 1);
        return Math.multiplyExact(base, multiplier + (ore ? 1 : 0));
    }

    static float feed(float current, int food, double gain) {
        double ceiling = Math.min(6, Math.max(0, food));
        if (!Double.isFinite(gain) || gain <= 0 || current >= ceiling) return current;
        return (float) Math.min(ceiling, current + gain);
    }

    static boolean refreshPotion(int currentAmplifier, int currentTicks, int amplifier, int ticks) {
        if (currentAmplifier > amplifier || currentTicks == -1) return false;
        return currentAmplifier < amplifier || currentTicks < ticks;
    }

    /**
     * Эквивалент независимых бросков на каждую единицу XP: геометрическое ожидание
     * до следующего успеха пропускает неудачи за один шаг. После полной починки
     * броски не нужны. Число итераций не больше износа, а не размера пакета XP.
     */
    static int repairs(int xp, int damage, double chance, DoubleSupplier random) {
        if (xp <= 0 || damage <= 0 || !Double.isFinite(chance) || chance <= 0) return 0;
        if (chance >= 1) return Math.min(xp, damage);
        long spent = 0;
        int repaired = 0;
        double failureLog = Math.log1p(-chance);
        while (spent < xp && repaired < damage) {
            double uniform = random.getAsDouble();
            if (!(uniform >= 0 && uniform < 1)) {
                throw new IllegalArgumentException("Случайная величина должна быть в [0, 1)");
            }
            double wait = Math.floor(Math.log1p(-uniform) / failureLog) + 1;
            if (wait > xp - spent) break;
            spent += (long) wait;
            repaired++;
        }
        return repaired;
    }
}
