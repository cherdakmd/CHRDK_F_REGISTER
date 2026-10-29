package ru.cherdakmd.chrdkenchants;

import java.util.*;

/** Чистые преобразования: неизвестные/невалидные чары не ремонтируются частично. */
final class WorkshopPolicy {
    record Split(Map<String, Integer> remainder, Map<String, Integer> extracted) { }
    static boolean valid(Map<String, Integer> data) {
        if (data == null || data.isEmpty()) return false;
        for (var entry : data.entrySet()) {
            var def = EnchantRegistry.byId(entry.getKey());
            if (def == null || !def.id().equals(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() < 1 || entry.getValue() > def.maxLevel()) return false;
        }
        return true;
    }
    static Map<String, Integer> cleanse(Map<String, Integer> data, String id) {
        var def = EnchantRegistry.byId(id);
        if (!valid(data) || def == null || !def.curse() || !data.containsKey(id)) return null;
        var next = new LinkedHashMap<>(data); next.remove(id); return Map.copyOf(next);
    }
    static Map<String, Integer> combine(Map<String, Integer> left, Map<String, Integer> right) {
        if (!valid(left) || left.size() != 1 || !left.equals(right)) return null;
        var entry = left.entrySet().iterator().next(); var def = EnchantRegistry.byId(entry.getKey());
        return entry.getValue() < def.maxLevel() ? Map.of(entry.getKey(), entry.getValue() + 1) : null;
    }
    static Split split(Map<String, Integer> data, String id, boolean vanilla) {
        if (!valid(data) || id == null || !data.containsKey(id) || data.size() < 2 && !vanilla) return null;
        var next = new LinkedHashMap<>(data); int level = next.remove(id);
        return new Split(Map.copyOf(next), Map.of(id, level));
    }
    private WorkshopPolicy() { }
}
