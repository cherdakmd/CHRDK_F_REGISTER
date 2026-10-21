package ru.cherdakmd.chrdktreasures;

/**
 * Арифметика серии находок.
 *
 * <p>Серия — это сколько кладов подряд игрок нашёл, не сделав паузы дольше
 * окна. Она вынесена в чистые функции, чтобы правила можно было проверить
 * тестами без сервера: хранилище отвечает только за числа, а решение «серия
 * продолжается или начинается заново» принимает этот класс.</p>
 *
 * <p>Серия никогда не бывает отрицательной: первый клад даёт 1, а не 0, иначе
 * бонус «каждая пятая находка» сдвигался бы на единицу.</p>
 */
public final class TreasureStreak {

    private TreasureStreak() {
    }

    /**
     * Новая длина серии.
     *
     * @param current      текущая серия (0 — находок ещё не было)
     * @param lastFindAt   время прошлой находки в миллисекундах (0 — не было)
     * @param now          текущее время в миллисекундах
     * @param windowMillis допустимая пауза между находками; 0 выключает серии
     */
    public static int next(int current, long lastFindAt, long now, long windowMillis) {
        if (current <= 0 || lastFindAt <= 0L || windowMillis <= 0L) {
            return 1;
        }
        if (now < lastFindAt) {
            // Часы сервера перевели назад: считаем серию прерванной, но не падаем.
            return 1;
        }
        return now - lastFindAt > windowMillis ? 1 : current + 1;
    }

    /** Пора ли выдать бонус за серию: каждая {@code every}-я находка. */
    public static boolean bonusDue(int streak, int every) {
        return every > 0 && streak > 0 && streak % every == 0;
    }

    /**
     * Сколько ещё миллисекунд серия жива. 0 — уже прервана или серий нет.
     * Нужно для подсказки в статистике.
     */
    public static long remainingMillis(long lastFindAt, long now, long windowMillis) {
        if (lastFindAt <= 0L || windowMillis <= 0L || now < lastFindAt) {
            return 0L;
        }
        return Math.max(0L, windowMillis - (now - lastFindAt));
    }
}
