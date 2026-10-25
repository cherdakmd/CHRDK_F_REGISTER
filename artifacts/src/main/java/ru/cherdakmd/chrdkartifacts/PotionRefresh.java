package ru.cherdakmd.chrdkartifacts;

/**
 * Правила пульса пассивных зелий:
 *  • длительность эффекта привязана к периоду пульса (раньше была жёсткая
 *    «трешка», и при `passive-refresh-ticks: 200` эффект моргал и пропадал);
 *  • слабое или короткое зелье не понижает эффект, который игрок уже получил
 *    извне (зелье, маяк, другой плагин) — раньше пульс перебивал Speed II
 *    на Speed I.
 *
 * <p>Класс чистый — тестируется без Bukkit.</p>
 */
public final class PotionRefresh {

    /** Запас сверх периода пульса, чтобы эффект не мигал между тиками. */
    static final int BUFFER_TICKS = 40;
    /** Минимальная длительность непульсового эффекта. */
    static final int MIN_TICKS = 20;

    private PotionRefresh() {
    }

    /**
     * Длительность эффекта в тиках.
     *
     * @param refreshTicks период пульса из конфига (10..200)
     * @param instant      мгновенный эффект (сытость): тикает ровно раз за пульс
     */
    public static int durationTicks(int refreshTicks, boolean instant) {
        if (instant) {
            return 1;
        }
        return Math.max(MIN_TICKS, refreshTicks) + BUFFER_TICKS;
    }

    /**
     * Нужно ли вообще вешать эффект.
     *
     * @param present               есть ли уже такой эффект
     * @param currentAmplifier      его текущий уровень
     * @param currentDurationTicks  остаток текущего эффекта в тиках
     * @param amplifier             уровень, который вешает артефакт
     * @param durationTicks         длительность, которую вешает артефакт
     */
    public static boolean shouldApply(boolean present, int currentAmplifier,
                                      int currentDurationTicks, int amplifier, int durationTicks) {
        if (!present) {
            return true;
        }
        // Чужой эффект сильнее — не понижаем.
        if (currentAmplifier > amplifier) {
            return false;
        }
        // Такой же уровень, но дольше — не укорачиваем.
        return currentAmplifier != amplifier || currentDurationTicks < durationTicks;
    }
}
