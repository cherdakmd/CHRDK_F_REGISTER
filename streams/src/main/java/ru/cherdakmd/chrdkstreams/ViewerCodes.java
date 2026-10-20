package ru.cherdakmd.chrdkstreams;

import java.util.Locale;

/**
 * Коды просмотра: стример показывает код на экране, зритель вводит его в игре.
 *
 * <p>Код меняется каждые несколько минут, поэтому ввод кода — доказательство,
 * что зритель действительно смотрит эфир сейчас, а не «пришёл один раз и ушёл».
 * Сверка терпимая: регистр, дефис и пробелы не важны.</p>
 */
final class ViewerCodes {

    static final String PREFIX = "WATCH";
    static final int MIN_WINDOW_MINUTES = 3;
    static final int MAX_WINDOW_MINUTES = 120;

    private ViewerCodes() {
    }

    /** Новый код вида {@code WATCH-7K2M}. */
    static String generate() {
        return StreamVerification.generate(PREFIX);
    }

    /** Пора менять код: окно истекло или код ещё не выдавался. */
    static boolean expired(long nowMillis, long codeUntil) {
        return codeUntil <= nowMillis;
    }

    /** Введённый код совпадает с текущим (регистр/дефисы/пробелы не важны). */
    static boolean matches(String input, String expected) {
        String left = normalize(input);
        String right = normalize(expected);
        return !left.isEmpty() && left.equals(right);
    }

    static String normalize(String code) {
        return code == null ? "" : StreamVerification.compact(code).toUpperCase(Locale.ROOT);
    }

    /**
     * Сколько минут просмотра засчитать за чек-ин.
     *
     * <p>Верхняя граница — окно кода: вернулся через час, а получишь максимум
     * одно окно. Так «зашёл один раз, потом неделю не смотрел» не превращается
     * в накопленный эфир.</p>
     *
     * @param nowMillis        текущее время
     * @param lastCheckInMillis прошлый зачтённый чек-ин; {@code 0} — ещё не было
     * @param windowMillis     окно кода
     */
    static long creditMillis(long nowMillis, long lastCheckInMillis, long windowMillis) {
        if (lastCheckInMillis <= 0 || windowMillis <= 0) {
            return 0L;
        }
        long elapsed = nowMillis - lastCheckInMillis;
        return elapsed <= 0 ? 0L : Math.min(elapsed, windowMillis);
    }

    /** Окно показа кода в миллисекундах, зажатое в разумные границы. */
    static long windowMillis(int minutes) {
        int safe = Math.clamp(minutes, MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES);
        return safe * 60_000L;
    }
}
