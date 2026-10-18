package ru.cherdakmd.chrdkartifacts;

/**
 * Арифметика урона для «Пера Феникса»: сколько урона реально дойдёт до здоровья
 * после щитов поглощения и смертелен ли удар. Раньше модуль сравнивал
 * {@code getFinalDamage()} с текущим здоровьем напрямую и сжигал десятиминутный
 * кулдаун на удар, который целиком гасили щиты.
 *
 * <p>Класс чистый — тестируется без Bukkit.</p>
 */
public final class CombatMath {

    private CombatMath() {
    }

    /** Урон, который дойдёт до здоровья: урон минус щиты поглощения (не ниже нуля). */
    public static double healthDamage(double finalDamage, double absorption) {
        double shield = Math.max(0.0, absorption);
        return Math.max(0.0, finalDamage - shield);
    }

    /**
     * Смертелен ли удар.
     *
     * @param health     текущее здоровье (0 и ниже — игрок уже мёртв, спасать нечего)
     * @param finalDamage урон после брони и сопротивления
     * @param absorption щиты поглощения
     */
    public static boolean isLethal(double health, double finalDamage, double absorption) {
        return health > 0 && healthDamage(finalDamage, absorption) >= health;
    }

    /**
     * Сколько здоровья вернуть при воскрешении: доля от максимума, но не меньше
     * одного сердца. Вырожденный максимум (0 или отрицательный) трактуется как 20.
     */
    public static double reviveHealth(double maxHealth, double fraction) {
        double max = maxHealth > 0 ? maxHealth : 20.0;
        double share = Math.min(1.0, Math.max(0.0, fraction));
        return Math.max(1.0, Math.min(max, max * share));
    }
}
