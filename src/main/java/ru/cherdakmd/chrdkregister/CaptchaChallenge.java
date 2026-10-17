package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Эскалационная проверка «ты не бот»: выбор нужного числа из нескольких вариантов.
 *
 * <p>Класс чистый (без Bukkit) и вынесен для юнит-тестов: основную защиту от перебора дают
 * лесенка задержек и временные блокировки из {@link AuthThrottle}, а капча — дополнительный
 * дешёвый фильтр против скриптов, который срабатывает только после заметной серии неудач.</p>
 *
 * @param expected правильный вариант (двузначное число строкой)
 * @param options  все варианты кнопок в перемешанном порядке, включая {@code expected}
 */
public record CaptchaChallenge(String expected, List<String> options) {

    /** Число вариантов в вызове (кнопок в диалоге / чисел в чат-подсказке). */
    public static final int OPTIONS_COUNT = 5;

    public CaptchaChallenge {
        if (expected == null || expected.isBlank()) {
            throw new IllegalArgumentException("expected must not be blank");
        }
        if (options == null || !options.contains(expected)) {
            throw new IllegalArgumentException("options must contain expected");
        }
        options = List.copyOf(options);
    }

    /** Генерирует вызов: правильное число + {@code OPTIONS_COUNT - 1} уникальных отвлекающих. */
    public static CaptchaChallenge generate(Random random) {
        String expected = nextNumber(random);
        LinkedHashSet<String> options = new LinkedHashSet<>();
        options.add(expected);
        while (options.size() < OPTIONS_COUNT) {
            options.add(nextNumber(random));
        }
        List<String> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled, random);
        return new CaptchaChallenge(expected, shuffled);
    }

    /** Двузначное число 10–99: легко прочитать на диалоге, не похоже на коды 2FA. */
    private static String nextNumber(Random random) {
        return Integer.toString(10 + random.nextInt(90));
    }

    /** Сверка ответа (сличение чисел; пробелы по краям игнорируются). */
    public boolean matches(String answer) {
        return answer != null && expected.equals(answer.trim());
    }

    /**
     * Нужна ли капча: счётчик неудач достиг порога, а снятая отметка о прохождении
     * относится к более ранней серии неудач (каждая новая ошибка после прохождения
     * снова включает проверку).
     *
     * @param totalFailures     исторический счётчик неудач аккаунта
     * @param clearedAtFailures значение счётчика на момент последнего прохождения (-1 — не проходил)
     * @param afterAttempts     порог из конфига (0 — капча выключена)
     */
    public static boolean required(int totalFailures, int clearedAtFailures, int afterAttempts) {
        return afterAttempts > 0 && totalFailures >= afterAttempts && clearedAtFailures < totalFailures;
    }
}
