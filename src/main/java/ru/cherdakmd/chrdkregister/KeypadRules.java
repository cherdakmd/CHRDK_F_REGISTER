package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Чистая логика «кнопочной» клавиатуры входа (ни одного класса Bukkit — юнит-тестируется):
 * страницы раскладки, допустимые символы, перетасовка и буфер набираемого пароля.
 *
 * <p>Зачем: пароль, набранный мышью по инвентарю с перетасовкой слотов после каждого нажатия,
 * нельзя восстановить по записи стрима (позиции кликов ничего не значат между нажатиями).</p>
 */
public final class KeypadRules {

    private KeypadRules() {
    }

    /** Максимум слотов раскладки (самый верхний низний ряд 54-слотового инвентаря — под управление). */
    public static final int MAX_LAYOUT_SLOTS = 45;
    /** Сколько дополнительных знаков берём из конфига. */
    public static final int MAX_EXTRA_SYMBOLS = 26;

    /** Страницы клавиатуры. */
    public enum Page {
        LOWER, UPPER, DIGITS_SYMBOLS;

        public Page next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Дедуплицирует строку знаков из конфига: выкидывает пробелы/управляющие/не-ASCII,
     * обрезает до {@link #MAX_EXTRA_SYMBOLS}.
     */
    public static String normalizeSymbols(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        Set<Character> seen = new LinkedHashSet<>();
        for (int i = 0; i < raw.length() && result.length() < MAX_EXTRA_SYMBOLS; i++) {
            char ch = raw.charAt(i);
            if (ch <= 0x20 || ch > 0x7E || ch == '\\' || ch == '"' || ch == '\'') {
                continue;
            }
            if (Character.isLetterOrDigit(ch)) {
                continue; // буквы и цифры уже есть на своих страницах
            }
            if (seen.add(ch)) {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * Символы страницы (каждый по разу). Если их больше {@link #MAX_LAYOUT_SLOTS}, список
     * обрезается — лишние знаки просто недоступны на клавиатуре (лог в конфиг-валидации).
     */
    public static List<Character> pageChars(Page page, String extraSymbolsRaw) {
        List<Character> chars = new ArrayList<>();
        switch (page) {
            case LOWER -> appendRange(chars, 'a', 'z');
            case UPPER -> appendRange(chars, 'A', 'Z');
            case DIGITS_SYMBOLS -> {
                appendRange(chars, '0', '9');
                for (char ch : normalizeSymbols(extraSymbolsRaw).toCharArray()) {
                    chars.add(ch);
                }
            }
        }
        if (chars.size() > MAX_LAYOUT_SLOTS) {
            return List.copyOf(chars.subList(0, MAX_LAYOUT_SLOTS));
        }
        return List.copyOf(chars);
    }

    /** Копия списка, перемешанная (Фишер-Йейтс по переданному источнику случайности). */
    public static List<Character> shuffled(List<Character> items, Random random) {
        List<Character> copy = new ArrayList<>(items);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return copy;
    }

    private static void appendRange(List<Character> target, char from, char to) {
        for (char ch = from; ch <= to; ch++) {
            target.add(ch);
        }
    }

    /**
     * Буфер набираемого пароля. Держит строку лишь до отправки; {@link #takeSecret()}
     * одновременно отдаёт пароль и стирает буфер.
     */
    public static final class Buffer {
        private final StringBuilder value = new StringBuilder();
        private final int maxLength;

        public Buffer(int maxLength) {
            this.maxLength = Math.max(1, maxLength);
        }

        /** false = уже набрана максимальная длина (символ не добавлен). */
        public boolean append(char ch) {
            if (isFull()) {
                return false;
            }
            value.append(ch);
            return true;
        }

        public boolean backspace() {
            if (value.length() == 0) {
                return false;
            }
            value.setLength(value.length() - 1);
            return true;
        }

        public void clear() {
            value.setLength(0);
        }

        public int size() {
            return value.length();
        }

        public boolean isFull() {
            return value.length() >= maxLength;
        }

        public boolean isEmpty() {
            return value.length() == 0;
        }

        /** Отдаёт набранный пароль и тут же стирает буфер (zero-retention). */
        public String takeSecret() {
            String secret = value.toString();
            value.setLength(0);
            return secret;
        }
    }
}
