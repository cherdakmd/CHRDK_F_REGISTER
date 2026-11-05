package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Публичная строка активных варнов для подвала TAB: одинаковая для всех игроков.
 *
 * <p>Чистая логика без Bukkit и PlaceholderAPI, поэтому покрывается обычным
 * JVM-тестом. Показываются только ник и счётчик «активные/порог»: причины,
 * даты, авторы выдачи, координаты и IP в TAB не уходят. Список ограничен
 * топ-N по числу активных варнов, остаток сворачивается в «+N ещё».</p>
 *
 * <p>Кадр включается отдельным флагом {@code moderation.warns-public-in-tab}
 * (по умолчанию {@code false}): публичный список нарушителей — решение
 * владельца сервера, и он не появляется молча после обновления плагина.</p>
 */
public final class PublicWarnSummary {

    private static final int MAX_NAME_LENGTH = 16;

    public record Row(String name, int active) {
    }

    private PublicWarnSummary() {
    }

    /** Пустая строка — кадр выключен; «нет» — включён, но нарушителей нет. */
    public static String format(List<Row> rows, int limit, int warnLimit) {
        if (limit <= 0 || warnLimit <= 0) {
            return "";
        }
        if (rows == null || rows.isEmpty()) {
            return "✅ Нарушителей нет";
        }
        List<Row> sorted = new ArrayList<>();
        for (Row row : rows) {
            String name = row == null ? null : safeName(row.name());
            if (row != null && row.active() > 0 && name != null) {
                sorted.add(new Row(name, row.active()));
            }
        }
        if (sorted.isEmpty()) {
            return "✅ Нарушителей нет";
        }
        sorted.sort(Comparator.<Row>comparingInt(Row::active).reversed()
                .thenComparing(row -> row.name().toLowerCase(Locale.ROOT)));
        int shown = Math.min(limit, sorted.size());
        StringBuilder out = new StringBuilder("⚠ ");
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(", ");
            }
            Row row = sorted.get(i);
            out.append(row.name()).append(' ')
                    .append(row.active()).append('/').append(warnLimit);
        }
        int rest = sorted.size() - shown;
        if (rest > 0) {
            out.append(" (+").append(rest).append(" ещё)");
        }
        return out.toString();
    }

    /** Bukkit-ник: латиница, цифры и подчёркивание, не длиннее 16 символов. */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length() && out.length() < MAX_NAME_LENGTH; i++) {
            char c = trimmed.charAt(i);
            if ((Character.isLetterOrDigit(c) && c < 128) || c == '_') {
                out.append(c);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        // Ключ варна хранится в нижнем регистре, а в TAB нужен читаемый ник.
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }
}
