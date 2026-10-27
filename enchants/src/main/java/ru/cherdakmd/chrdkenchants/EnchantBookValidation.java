package ru.cherdakmd.chrdkenchants;

import java.util.LinkedHashMap;
import java.util.Map;

/** Строгая граница переноса, не меняющая терпимый кодек старых эффектов. null = отказ целиком. */
final class EnchantBookValidation {
    static Map<String, Integer> read(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) return null;
        String[] tokens = raw.split(";", -1);
        if (tokens.length > EnchantRegistry.size()) return null;
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String token : tokens) {
            String[] parts = token.split(":", -1);
            if (parts.length != 2) return null;
            String id = parts[0].strip(); EnchantDef def = EnchantRegistry.byId(id);
            if (def == null || !def.id().equals(id) || parsed.containsKey(id)) return null;
            int level;
            try { level = Integer.parseInt(parts[1].strip()); } catch (NumberFormatException invalid) { return null; }
            if (level < 1 || level > def.maxLevel()) return null;
            parsed.put(id, level);
        }
        return Map.copyOf(parsed);
    }
    private EnchantBookValidation() { }
}
