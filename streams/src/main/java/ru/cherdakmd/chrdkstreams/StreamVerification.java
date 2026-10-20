package ru.cherdakmd.chrdkstreams;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

/**
 * Подтверждение владения каналом: код в названии эфира.
 *
 * <p>Плагин не может «войти» на Twitch под стримером и не должен хранить его
 * пароли. Зато он видит название эфира через API. Поэтому привязка такая:
 * игрок пишет {@code /stream link twitch nick}, получает код вида
 * {@code CHRDK-7K2M}, вставляет его в название стрима — и только тогда канал
 * считается его. Чужой канал так не подтвердить: код знает только владелец
 * аккаунта на сервере и он же — автор эфира.</p>
 *
 * <p>Класс чистый: генерация и сверка проверяются тестами.</p>
 */
final class StreamVerification {

    /** Префикс узнаваемый и не встречается в обычных названиях эфиров. */
    static final String PREFIX = "CHRDK";
    static final int RANDOM_LENGTH = 4;
    /** Без похожих символов: 0/O, 1/I/L — их путают при ручном вводе с экрана. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final SecureRandom SECURE = new SecureRandom();

    private StreamVerification() {
    }

    /** Новый код: {@code CHRDK-XXXX}. */
    static String generate() {
        return generate(SECURE);
    }

    /** Генерация с явным источником случайности — для тестов. */
    static String generate(Random random) {
        StringBuilder builder = new StringBuilder(PREFIX).append('-');
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    /**
     * Есть ли код в названии эфира.
     *
     * <p>Сверка терпимая к оформлению: регистр, дефисы, пробелы и эмодзи между
     * символами не мешают. {@code «CHRDK 7k2m | играем»} и {@code «chrdk-7k2m»}
     * считаются одним и тем же кодом.</p>
     */
    static boolean matches(String title, String code) {
        String expected = compact(code);
        if (expected.isEmpty()) {
            return false;
        }
        return compact(title).contains(expected);
    }

    /** Оставляет только буквы и цифры в верхнем регистре: «CHRDK-7K2M» → «CHRDK7K2M». */
    static String compact(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toUpperCase(current));
            }
        }
        return builder.toString();
    }

    /** Код выглядит как код, выданный плагином (защита от мусора в data.json). */
    static boolean valid(String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith(PREFIX + "-")) {
            return false;
        }
        String tail = normalized.substring(PREFIX.length() + 1);
        if (tail.length() != RANDOM_LENGTH) {
            return false;
        }
        for (int index = 0; index < tail.length(); index++) {
            if (ALPHABET.indexOf(tail.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }
}
