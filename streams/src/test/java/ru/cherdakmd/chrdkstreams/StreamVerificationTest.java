package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Код подтверждения канала: формат, генерация и терпимая сверка с названием эфира. */
class StreamVerificationTest {

    @Test
    void generatedCodeHasExpectedShape() {
        String code = StreamVerification.generate();

        assertTrue(code.matches("CHRDK-[A-Z2-9]{4}"), "код вида CHRDK-7K2M, получено: " + code);
        assertTrue(StreamVerification.valid(code));
    }

    @Test
    void generatedCodesAvoidLookalikeSymbols() {
        Random random = new Random(42);
        for (int index = 0; index < 50; index++) {
            String code = StreamVerification.generate(random);
            String tail = code.substring("CHRDK-".length());
            for (char symbol : tail.toCharArray()) {
                assertTrue("ABCDEFGHJKMNPQRSTUVWXYZ23456789".indexOf(symbol) >= 0,
                        "символ " + symbol + " не должен встречаться в коде");
            }
        }
    }

    @Test
    void matchesToleratesFormatting() {
        assertTrue(StreamVerification.matches("CHRDK-7K2M", "CHRDK-7K2M"));
        assertTrue(StreamVerification.matches("chrdk 7k2m | играем", "CHRDK-7K2M"));
        assertTrue(StreamVerification.matches("Сегодня стрим! chrdk7k2m", "CHRDK-7K2M"));
        assertTrue(StreamVerification.matches("CHRDK - 7K2M — день 3", "CHRDK-7K2M"));
        assertTrue(StreamVerification.matches("🎮CHRDK_7K2M🎮", "CHRDK-7K2M"));
    }

    @Test
    void doesNotMatchOtherCodes() {
        assertFalse(StreamVerification.matches("CHRDK-7K2N", "CHRDK-7K2M"));
        assertFalse(StreamVerification.matches("Просто стрим без кода", "CHRDK-7K2M"));
        assertFalse(StreamVerification.matches("", "CHRDK-7K2M"));
        assertFalse(StreamVerification.matches(null, "CHRDK-7K2M"));
        assertFalse(StreamVerification.matches("CHRDK-7K2M", ""));
        assertFalse(StreamVerification.matches("CHRDK-7K2M", null));
    }

    @Test
    void validRejectsGarbage() {
        assertTrue(StreamVerification.valid("chrdk-7k2m"), "регистр не важен");
        assertTrue(StreamVerification.valid("  CHRDK-ABCD  "), "пробелы по краям допустимы");
        assertFalse(StreamVerification.valid(null));
        assertFalse(StreamVerification.valid(""));
        assertFalse(StreamVerification.valid("CHRDK-ABC"));
        assertFalse(StreamVerification.valid("CHRDK-ABCDE"));
        assertFalse(StreamVerification.valid("ABCD-ABCD"));
        assertFalse(StreamVerification.valid("CHRDK-AB0D"), "0 не входит в алфавит");
        assertFalse(StreamVerification.valid("CHRDK-ABID"), "I не входит в алфавит");
        assertFalse(StreamVerification.valid("CHRDK-ABLD"), "L не входит в алфавит");
    }

    @Test
    void compactKeepsLettersAndDigits() {
        assertEquals("CHRDK7K2M", StreamVerification.compact(" CHRDK - 7k2m! "));
        assertEquals("", StreamVerification.compact(null));
    }
}
