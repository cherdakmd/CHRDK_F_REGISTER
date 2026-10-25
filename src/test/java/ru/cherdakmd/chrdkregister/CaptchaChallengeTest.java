package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эскалационная капча (1.4.4): генерация вызова из чисел, сверка ответа
 * и пороговая формула включения по счётчику неудачных попыток.
 */
class CaptchaChallengeTest {

    private static final Pattern TWO_DIGITS = Pattern.compile("\\d{2}");

    @Test
    void generateProducesUniqueOptionsContainingExpected() {
        Random random = new Random(42L);
        for (int round = 0; round < 200; round++) {
            CaptchaChallenge challenge = CaptchaChallenge.generate(random);
            assertEquals(CaptchaChallenge.OPTIONS_COUNT, challenge.options().size());
            assertEquals(CaptchaChallenge.OPTIONS_COUNT, new HashSet<>(challenge.options()).size(),
                    "варианты не должны повторяться");
            assertTrue(challenge.options().contains(challenge.expected()),
                    "правильный вариант обязан быть среди кнопок");
            for (String option : challenge.options()) {
                assertTrue(TWO_DIGITS.matcher(option).matches(),
                        "вариант должен быть двузначным числом: " + option);
            }
        }
    }

    @Test
    void matchesAcceptsExactAnswerWithWhitespace() {
        CaptchaChallenge challenge = new CaptchaChallenge("42",
                java.util.List.of("17", "42", "83", "55", "91"));
        assertTrue(challenge.matches("42"));
        assertTrue(challenge.matches(" 42 "));
        assertFalse(challenge.matches("17"));
        assertFalse(challenge.matches("042"));
        assertFalse(challenge.matches(""));
        assertFalse(challenge.matches(null));
    }

    @Test
    void constructorRejectsBrokenChallenges() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaptchaChallenge("", java.util.List.of("42")));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptchaChallenge("42", java.util.List.of("17", "83")),
                "options без expected запрещены");
        assertThrows(IllegalArgumentException.class,
                () -> new CaptchaChallenge("42", null));
    }

    @Test
    void requiredFollowsFailureThreshold() {
        // капча выключена (0)
        assertFalse(CaptchaChallenge.required(10, -1, 0));
        // ещё не набран порог неудач
        assertFalse(CaptchaChallenge.required(2, -1, 3));
        // порог достигнут, проверка не пройдена
        assertTrue(CaptchaChallenge.required(3, -1, 3));
        assertTrue(CaptchaChallenge.required(4, -1, 3));
        // проверка пройдена на текущем значении счётчика
        assertFalse(CaptchaChallenge.required(3, 3, 3));
        // новая ошибка после прохождения снова включает проверку
        assertTrue(CaptchaChallenge.required(4, 3, 3));
    }
}
