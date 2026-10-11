package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Страницы раскладки, нормализация знаков, перетасовка и буфер пароля (без Bukkit). */
class KeypadRulesTest {

    @Test
    void pagesProvideExpectedChars() {
        List<Character> lower = KeypadRules.pageChars(KeypadRules.Page.LOWER, "");
        List<Character> upper = KeypadRules.pageChars(KeypadRules.Page.UPPER, "");
        List<Character> digits = KeypadRules.pageChars(KeypadRules.Page.DIGITS_SYMBOLS, "");
        assertEquals(26, lower.size());
        assertTrue(lower.contains('a') && lower.contains('z'));
        assertTrue(upper.contains('A') && upper.contains('Z'));
        assertEquals(10, digits.size());
        assertTrue(digits.contains('0') && digits.contains('9'));
        assertTrue(lower.size() <= KeypadRules.MAX_LAYOUT_SLOTS);
    }

    @Test
    void pagesRotate() {
        assertEquals(KeypadRules.Page.UPPER, KeypadRules.Page.LOWER.next());
        assertEquals(KeypadRules.Page.DIGITS_SYMBOLS, KeypadRules.Page.UPPER.next());
        assertEquals(KeypadRules.Page.LOWER, KeypadRules.Page.DIGITS_SYMBOLS.next());
    }

    @Test
    void symbolsNormalized() {
        assertEquals("!@#", KeypadRules.normalizeSymbols("!@#!#@"), "дубликаты идут лесом");
        assertEquals("", KeypadRules.normalizeSymbols(null));
        assertEquals("", KeypadRules.normalizeSymbols("   "));
        assertEquals("$", KeypadRules.normalizeSymbols("Мил$есть"), "не-ASCII отброшена");
        assertEquals("?", KeypadRules.normalizeSymbols(" 1?a"), "буквы/цифры/пробелы отброшены, знак остался");
        String crowded = "!#$%&()*+,-./:;<=>?~^_`|'{[}]@";
        assertEquals(KeypadRules.MAX_EXTRA_SYMBOLS,
                KeypadRules.normalizeSymbols(crowded).length(), "обрезка по лимиту");
    }

    @Test
    void digitsPageAbsorbsSymbolsAndCaps() {
        String many = "!#$%&()*+,-./:;<=>?~^_`|'";
        List<Character> page = KeypadRules.pageChars(KeypadRules.Page.DIGITS_SYMBOLS, many);
        assertTrue(page.size() <= KeypadRules.MAX_LAYOUT_SLOTS);
        assertEquals(page.size(), new HashSet<>(page).size(), "дубликатов на странице нет");
        assertTrue(page.contains('0'), "цифры не теряются от груды знаков");
    }

    @Test
    void shuffleKeepsPermutation() {
        Random random = new Random(31);
        List<Character> chars = KeypadRules.pageChars(KeypadRules.Page.LOWER, "");
        for (int i = 0; i < 200; i++) {
            List<Character> shuffled = KeypadRules.shuffled(chars, random);
            assertEquals(new HashSet<>(chars), new HashSet<>(shuffled));
            assertEquals(chars.size(), shuffled.size());
        }
        // и хотя бы одно различие на большом числе тасовок при фиксированном seed
        Set<List<Character>> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seen.add(KeypadRules.shuffled(chars, random));
        }
        assertTrue(seen.size() > 1, "перетасовка не должна быть стабильной");
    }

    @Test
    void bufferLifecycle() {
        KeypadRules.Buffer buffer = new KeypadRules.Buffer(3);
        assertTrue(buffer.append('a'));
        assertTrue(buffer.append('b'));
        assertTrue(buffer.append('c'));
        assertFalse(buffer.append('d'), "за предел максимума — нельзя");
        assertTrue(buffer.isFull());
        assertTrue(buffer.backspace());
        assertEquals(2, buffer.size());
        assertEquals("ab", buffer.takeSecret());
        assertTrue(buffer.isEmpty(), "takeSecret стирает буфер");
        assertFalse(buffer.backspace());
        buffer.append('x');
        buffer.clear();
        assertEquals(0, buffer.size());
    }

    @Test
    void bufferMaxIsPositiveEvenOnDegenerateConfig() {
        KeypadRules.Buffer buffer = new KeypadRules.Buffer(0);
        assertTrue(buffer.append('z'));
        assertFalse(buffer.append('y'));
    }
}
