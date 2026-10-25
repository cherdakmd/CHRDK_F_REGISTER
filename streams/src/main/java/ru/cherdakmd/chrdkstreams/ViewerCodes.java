package ru.cherdakmd.chrdkstreams;

import java.util.Locale;

/**
 * Код просмотра: стример показывает его в эфире, зритель вводит в игре.
 *
 * <p>Код меняется каждые несколько минут ({@code viewer-code-minutes}). Это и
 * есть доказательство просмотра: чтобы получить следующий отрезок минут,
 * зритель должен увидеть новый код — то есть действительно смотреть эфир, а не
 * «зайти один раз и уйти».</p>
 *
 * <p>Класс чистый — только строки и арифметика: генерация, сверка и границы
 * проверяются юнит-тестами.</p>
 */
final class ViewerCodes {

    /** Префикс отличается от кода подтверждения канала ({@code CHRDK}), чтобы их не путали. */
    static final String PREFIX = "WATCH";
    /** Слишком часто менять код неудобно, слишком редко — нечестно. */
    static final int MIN_WINDOW_MINUTES = 3;
    static final int MAX_WINDOW_MINUTES = 60;

    private ViewerCodes() {
    }

    /** Новый код вида {@code WATCH-7K2M}. */
    static String generate() {
        return StreamVerification.generate(PREFIX);
    }

    /**
     * Совпадает ли введённый код с текущим.
     *
     * <p>Сверка терпимая к тому, как код переписали с экрана: регистр, дефис,
     * пробелы и «лишние» знаки не важны. Пустой код не совпадает ни с чем.</p>
     */
    static boolean matches(String input, String expected) {
        String left = normalize(input);
        String right = normalize(expected);
        return !left.isEmpty() && left.equals(right);
    }

    /** Только буквы и цифры в верхнем регистре: {@code «watch-7k2m» → «WATCH7K2M»}. */
    static String normalize(String code) {
        return StreamVerification.compact(code).toUpperCase(Locale.ROOT);
    }

    /** Окно кода истекло (или ещё не выдавалось) — нужен новый код. */
    static boolean expired(long codeUntil, long now) {
        return codeUntil <= now;
    }

    /** Сколько осталось от окна кода; для подсказки «действует ещё N мин.». */
    static long remainingMillis(long codeUntil, long now) {
        return Math.max(0L, codeUntil - now);
    }

    /** Окно кода в миллисекундах, зажатое в разумные границы. */
    static long windowMillis(int minutes) {
        int safe = Math.clamp(minutes, MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES);
        return safe * 60_000L;
    }

    /**
     * Сколько миллисекунд просмотра засчитать за чек-ин.
     *
     * <p>Не больше окна кода: если зритель вернулся через час, он получит
     * максимум одно окно — «накопить за неделю отсутствия» нельзя.</p>
     *
     * @param elapsedMillis сколько прошло с прошлого чек-ина
     * @param windowMillis  окно действия кода
     */
    static long creditMillis(long elapsedMillis, long windowMillis) {
        if (elapsedMillis <= 0L || windowMillis <= 0L) {
            return 0L;
        }
        return Math.min(elapsedMillis, windowMillis);
    }
}
