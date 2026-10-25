package ru.cherdakmd.chrdkevents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Форматирование времени для событий (1.4.160).
 *
 * <p>Отдельный маленький класс, потому что «осталось 04:35» нужно и в
 * bossbar, и в профиле, и в командах, а формат должен совпадать до символа.
 * Никаких зависимостей — проверяется тестом.</p>
 */
final class EventText {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private EventText() {
    }

    /**
     * Время в формате {@code MM:SS} или {@code H:MM:SS}.
     *
     * @param seconds секунды; отрицательные считаются нулём
     */
    static String clock(long seconds) {
        long total = Math.max(0L, seconds);
        long hours = total / 3_600L;
        long minutes = (total % 3_600L) / 60L;
        long rest = total % 60L;
        if (hours > 0L) {
            return hours + ":" + two(minutes) + ":" + two(rest);
        }
        return two(minutes) + ":" + two(rest);
    }

    /**
     * Длительность словами: «45 с», «5 мин», «1 ч 20 мин».
     *
     * @param seconds секунды; отрицательные считаются нулём
     */
    static String duration(long seconds) {
        long total = Math.max(0L, seconds);
        if (total < 60L) {
            return total + " с";
        }
        long hours = total / 3_600L;
        long minutes = (total % 3_600L) / 60L;
        if (hours > 0L) {
            return minutes > 0L ? hours + " ч " + minutes + " мин" : hours + " ч";
        }
        return minutes + " мин";
    }

    /**
     * Дата и время записи истории: {@code 24.10 18:05}.
     *
     * @param millis время в миллисекундах; 0 и меньше — прочерк
     */
    static String stamp(long millis) {
        return millis <= 0L ? "—" : STAMP.format(Instant.ofEpochMilli(millis));
    }

    private static String two(long value) {
        return value < 10L ? "0" + value : Long.toString(value);
    }
}
