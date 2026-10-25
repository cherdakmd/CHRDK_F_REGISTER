package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Приветствие привата: нормализация текста и флаг наличия. */
class ClaimGreetingTest {

    @Test
    void normalizesWhitespaceAndLineBreaks() {
        assertEquals("Добро пожаловать, путник!",
                Claim.normalizeGreeting("  Добро\nпожаловать,\rпутник!  "));
        assertEquals("a b c", Claim.normalizeGreeting("a\tb\nc"));
        assertEquals("", Claim.normalizeGreeting("   "));
        assertEquals("", Claim.normalizeGreeting(null));
    }

    @Test
    void clampsToMaximumLength() {
        String longText = "х".repeat(Claim.MAX_GREETING + 25);
        String normalized = Claim.normalizeGreeting(longText);
        assertEquals(Claim.MAX_GREETING, normalized.length());
    }

    @Test
    void trimsSpaceLeftByClamp() {
        String text = "x".repeat(Claim.MAX_GREETING - 1) + " yy";
        String normalized = Claim.normalizeGreeting(text);
        assertEquals(Claim.MAX_GREETING - 1, normalized.length(),
                "пробел на границе обрезки не должен оставаться в конце");
        assertFalse(normalized.endsWith(" "));
    }

    @Test
    void hasGreetingIgnoresNullAndBlank() {
        Claim claim = new Claim("world", 0, 0, "Steve", 1_000L);
        assertFalse(claim.hasGreeting());
        claim.greeting = "  ";
        assertFalse(claim.hasGreeting());
        claim.greeting = "Привет!";
        assertTrue(claim.hasGreeting());
    }
}
