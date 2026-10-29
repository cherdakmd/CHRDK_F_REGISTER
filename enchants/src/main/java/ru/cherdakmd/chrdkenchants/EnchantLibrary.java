package ru.cherdakmd.chrdkenchants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Строгий ограниченный индекс лучших уровней из настоящих книг; никаких наград. */
final class EnchantLibrary {
    static final int PAGE_SIZE = 10;
    static Map<String, Integer> read(String raw) {
        if (raw == null) return Map.of();
        if (raw.length() > 4099 || !raw.startsWith("v1|")) return null;
        return raw.equals("v1|") ? Map.of() : EnchantBookValidation.read(raw.substring(3));
    }
    static String encode(Map<String, Integer> entries) { return "v1|" + EnchantCodec.encode(entries); }
    static Map<String, Integer> merge(Map<String, Integer> before, Map<String, Integer> book) {
        if (before == null || book == null) return before;
        Map<String, Integer> result = new LinkedHashMap<>(before);
        book.forEach((id, level) -> result.merge(id, level, Math::max));
        // Не принимаем произвольные идентификаторы/уровни даже от ошибочного вызывающего кода.
        return read(encode(result));
    }
    static int pages(int count) { return Math.max(1, (int) ((Math.max(0L, count) + PAGE_SIZE - 1) / PAGE_SIZE)); }
    static List<Map.Entry<String, Integer>> page(Map<String, Integer> entries, int requested) {
        var ordered = entries.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).sorted(Map.Entry.comparingByKey()).toList();
        int page = Math.clamp((long) requested - 1, 0, pages(ordered.size()) - 1);
        int start = page * PAGE_SIZE;
        return List.copyOf(ordered.subList(start, Math.min(ordered.size(), start + PAGE_SIZE)));
    }
    private EnchantLibrary() { }
}
