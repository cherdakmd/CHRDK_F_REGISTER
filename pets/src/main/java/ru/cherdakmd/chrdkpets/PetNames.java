package ru.cherdakmd.chrdkpets;

import java.util.regex.Pattern;

/**
 * Клички питомцев: нормализация пользовательского ввода без Bukkit.
 *
 * <p>Имя вводит игрок, а показывается оно всем в мире над головой питомца.
 * Поэтому разметку вырезаем на входе, а не надеемся на экранирование при
 * выводе: MiniMessage-теги, старые {@code §}/{@code &}-коды и управляющие символы не
 * должны попадать ни в хранилище, ни в {@code customName}.</p>
 *
 * <p>Разрешены буквы (включая кириллицу), цифры, пробел, дефис, апостроф и
 * подчёркивание. Остальное заменяется пробелом, пробелы схлопываются, длина
 * обрезается по конфигу.</p>
 */
public final class PetNames {

    private static final Pattern MINI_TAG = Pattern.compile("<[^<>]{1,64}>");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[§&][0-9a-fk-or]");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern FORBIDDEN = Pattern.compile("[^\\p{L}\\p{N} _'\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private PetNames() {
    }

    /**
     * Чистое имя или пустая строка, если после чистки ничего не осталось.
     *
     * @param raw       ввод игрока (может быть {@code null})
     * @param maxLength максимальная длина в символах (клампится 1..32)
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        int limit = Math.max(1, Math.min(32, maxLength));
        String text = MINI_TAG.matcher(raw).replaceAll(" ");
        text = LEGACY_CODE.matcher(text).replaceAll(" ");
        text = CONTROL.matcher(text).replaceAll("");
        text = FORBIDDEN.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        if (text.length() > limit) {
            text = text.substring(0, limit).trim();
        }
        return text;
    }

    /** Годится ли имя после {@link #sanitize(String, int)}. */
    public static boolean valid(String sanitized, int minLength, int maxLength) {
        if (sanitized == null || sanitized.isBlank()) {
            return false;
        }
        int min = Math.max(1, Math.min(32, minLength));
        int max = Math.max(min, Math.min(32, maxLength));
        return sanitized.length() >= min && sanitized.length() <= max;
    }
}
