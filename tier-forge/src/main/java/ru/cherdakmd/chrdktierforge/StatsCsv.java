package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Выгрузка статистики находок в CSV (1.4.99).
 *
 * <p>Хозяину сервера нужен срез «кто сколько наковал» для таблиц и Discord.
 * Формат: первая строка — заголовок {@code uuid,name,cursed,…,legendary,total},
 * дальше по строке на игрока. Порядок — по общему счёту по убыванию, при равенстве
 * по UUID, поэтому файл стабилен между выгрузками.
 *
 * <p>Поля экранируются по RFC 4180: значение с запятой, кавычкой или переводом
 * строки заключается в кавычки, внутренние кавычки удваиваются. Разделитель —
 * запятая, перевод строки — {@code \n} (Excel и LibreOffice читают одинаково).
 *
 * <p>Чистая логика — покрыто {@link StatsCsvTest}.
 */
final class StatsCsv {

    private StatsCsv() {
    }

    /** Заголовок таблицы в порядке {@link TierDef#LADDER}. */
    static String header() {
        StringBuilder line = new StringBuilder("uuid,name");
        for (String tier : TierDef.LADDER) {
            line.append(',').append(tier);
        }
        return line.append(",total").toString();
    }

    /**
     * Вся таблица одной строкой.
     *
     * @param snapshot счётчики игроков (тир → количество)
     * @param names    известные ники; отсутствующий ник заменяется UUID
     */
    static String render(Map<UUID, Map<String, Integer>> snapshot,
                         Map<UUID, String> names) {
        StringBuilder out = new StringBuilder(header()).append('\n');
        if (snapshot == null || snapshot.isEmpty()) {
            return out.toString();
        }
        List<Map.Entry<UUID, Map<String, Integer>>> rows = new ArrayList<>(snapshot.entrySet());
        rows.sort(Comparator
                .comparingInt((Map.Entry<UUID, Map<String, Integer>> entry) -> total(entry.getValue()))
                .reversed()
                .thenComparing(entry -> entry.getKey().toString()));
        for (Map.Entry<UUID, Map<String, Integer>> entry : rows) {
            Map<String, Integer> row = entry.getValue() == null ? Map.of() : entry.getValue();
            String name = names == null ? null : names.get(entry.getKey());
            if (name == null || name.isBlank()) {
                name = entry.getKey().toString();
            }
            out.append(escape(entry.getKey().toString()))
                    .append(',').append(escape(name));
            for (String tier : TierDef.LADDER) {
                out.append(',').append(Math.max(0, row.getOrDefault(tier, 0)));
            }
            out.append(',').append(total(row)).append('\n');
        }
        return out.toString();
    }

    /** Экранирование поля по RFC 4180. */
    static String escape(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** Сумма по всем тирам строки (отрицательные значения не уменьшают итог). */
    private static int total(Map<String, Integer> row) {
        int sum = 0;
        for (String tier : TierDef.LADDER) {
            Integer count = row.get(tier);
            if (count != null && count > 0) {
                sum += count;
            }
        }
        return sum;
    }

    /** Имя файла выгрузки для папки плагина. */
    static String fileName() {
        return "stats-export.csv";
    }
}
