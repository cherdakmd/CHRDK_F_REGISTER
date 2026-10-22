package ru.cherdakmd.chrdkpets;

import java.util.ArrayList;
import java.util.List;

/**
 * Дневник питомца — короткая лента событий, которую видно в {@code /pet diary}.
 *
 * <p>Запись хранится строкой {@code время|событие|аргумент}: так файл остаётся
 * читаемым, а форматирование (дата, подстановки, цвета) делает команда через
 * {@code messages.yml}. Лента обрезается по размеру из конфига, поэтому
 * {@code pets.json} не растёт бесконечно.</p>
 */
public final class PetDiary {

    /** Разделитель полей записи; в аргументе он заменяется пробелом. */
    private static final char SEPARATOR = '|';

    /** Одно событие дневника. */
    public record Entry(long at, String code, String arg) {
    }

    private PetDiary() {
    }

    /** Кодирует событие в строку для JSON. */
    public static String encode(Entry entry) {
        if (entry == null) {
            return "";
        }
        String code = entry.code() == null ? "" : entry.code().trim();
        String arg = entry.arg() == null ? "" : entry.arg().replace(SEPARATOR, ' ').trim();
        return entry.at() + String.valueOf(SEPARATOR) + code + SEPARATOR + arg;
    }

    /** Разбирает строку; повреждённая запись возвращает {@code null}. */
    public static Entry decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 2) {
            return null;
        }
        long at;
        try {
            at = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException exception) {
            return null;
        }
        String code = parts[1].trim();
        if (code.isEmpty()) {
            return null;
        }
        String arg = parts.length > 2 ? parts[2].trim() : "";
        return new Entry(Math.max(0L, at), code, arg);
    }

    /**
     * Добавляет событие в конец ленты и обрезает её до {@code limit}.
     * Возвращает новый неизменяемый список — хранилище подменяет ссылку целиком.
     */
    public static List<String> append(List<String> raw, Entry entry, int limit) {
        int max = Math.max(0, Math.min(100, limit));
        if (max == 0) {
            return List.of();
        }
        if (entry == null) {
            return raw == null ? List.of() : List.copyOf(raw);
        }
        List<String> out = new ArrayList<>(raw == null ? List.<String>of() : raw);
        out.add(encode(entry));
        while (out.size() > max) {
            out.remove(0);
        }
        return List.copyOf(out);
    }
}
