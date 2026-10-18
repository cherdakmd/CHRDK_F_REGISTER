package ru.cherdakmd.chrdktierforge;

/**
 * Порог «Последнего рубежа» (1.4.98).
 *
 * <p>Свойство спасает от смертельного удара, поэтому решать это надо по итоговому
 * урону — уже после парирования и срезов «Стойкости», «Сапёра» и «Антимага» — и с
 * учётом поглощения. Раньше проверка стояла до парирования и не видела
 * поглощение: удар, который прошёл бы в ноль или был бы съеден щитами, всё равно
 * сжигал кулдаун на 60 секунд и оставлял игрока с 1 HP.
 *
 * <p>Чистая арифметика — покрыто {@link SurvivalMathTest}.
 */
final class SurvivalMath {

    private SurvivalMath() {
    }

    /** Сколько урона реально дойдёт до здоровья: поглощение съедает свою часть. */
    static double healthDamage(double incoming, double absorption) {
        if (incoming <= 0) {
            return 0;
        }
        double shield = Math.max(0, absorption);
        return Math.max(0, incoming - shield);
    }

    /** Удар смертелен, если после поглощения он не меньше текущего здоровья. */
    static boolean isLethal(double health, double absorption, double incoming) {
        if (health <= 0) {
            return false; // мёртвого не спасаем, событие уже не наше
        }
        return healthDamage(incoming, absorption) >= health;
    }
}
