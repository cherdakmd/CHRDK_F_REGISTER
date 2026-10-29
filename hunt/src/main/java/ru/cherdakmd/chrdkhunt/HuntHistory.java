package ru.cherdakmd.chrdkhunt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.cherdakmd.chrdkregister.api.MobProgressionKeys;

/** Последние 20 собственных природных уровневых убийств. Не источник наград/статистики. */
final class HuntHistory {
    static final int LIMIT = 20, PAGE_SIZE = 5;
    private static final long MAX_TIME = 253402300799999L;
    record Entry(UUID victim, long time, String type, int level, int elite, String world) {
        Entry {
            if (victim == null || time <= 0 || time > MAX_TIME || type == null
                    || !type.matches("[A-Z][A-Z0-9_]{0,63}") || level < 1 || level > 30
                    || elite < 0 || elite > MobProgressionKeys.MAX_ELITE || world == null
                    || world.isBlank() || world.length() > 128 || world.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid hunt history entry");
        }
    }
    static boolean append(List<Entry> rows, Entry entry) {
        if (entry == null || rows.stream().anyMatch(row -> row.victim.equals(entry.victim))) return false;
        rows.add(entry);
        while (rows.size() > LIMIT) rows.removeFirst();
        return true;
    }
    static List<Entry> read(Object raw) {
        var rows = new ArrayList<Entry>();
        if (!(raw instanceof List<?> list)) return rows;
        // Повреждённый файл не заставляет перебирать неограниченную историю.
        for (int i = Math.max(0, list.size() - LIMIT); i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> row)) continue;
            try {
                String id = text(row.get("victim")); UUID victim = UUID.fromString(id);
                if (!victim.toString().equalsIgnoreCase(id)) continue;
                append(rows, new Entry(victim, integer(row.get("time"), MAX_TIME), text(row.get("type")),
                        (int) integer(row.get("level"), 30), (int) integer(row.get("elite"), MobProgressionKeys.MAX_ELITE),
                        text(row.get("world"))));
            } catch (IllegalArgumentException ignored) { /* Битая строка не затрагивает кошелёк. */ }
        }
        return rows;
    }
    static List<Map<String, Object>> write(List<Entry> rows) {
        return rows.stream().map(row -> Map.<String, Object>of("victim", row.victim.toString(), "time", row.time,
                "type", row.type, "level", row.level, "elite", row.elite, "world", row.world)).toList();
    }
    static int pages(int size) { return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE); }
    static int page(String raw, int size) {
        if (raw == null) return 1;
        if (!raw.matches("[1-9][0-9]{0,2}")) return 0;
        int page = Integer.parseInt(raw);
        return page <= pages(size) ? page : 0;
    }
    private static String text(Object raw) { return raw instanceof String text ? text : ""; }
    private static long integer(Object raw, long max) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException();
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > max || value != Math.rint(value)) throw new IllegalArgumentException();
        return number.longValue();
    }
    private HuntHistory() { }
}
