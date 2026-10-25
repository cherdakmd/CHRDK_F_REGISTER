package ru.cherdakmd.chrdkenchants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сериализация набора чар в строку PDC: "id:level;id:level". Терпима к мусору,
 * соблюдает лимит уровня чар по реестру? Нет — кодек чист, реестр не трогает.
 */
public final class EnchantCodec {

    private EnchantCodec() {
    }

    /** Парсит строку в карту id→уровень (сохраняет порядок, бережно игнорирует мусор). */
    public static Map<String, Integer> decode(String raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String token : raw.split(";")) {
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) continue;
            String id = token.substring(0, colon).trim();
            if (id.isEmpty()) continue;
            int level;
            try {
                level = Integer.parseInt(token.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (level <= 0 || level > 10) continue;
            result.merge(id, level, Math::max);
        }
        return result;
    }

    /** Сериализует карту обратно в строку (без пустых записей, порядок сохранён). */
    public static String encode(Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) {
            return "";
        }
        // сортируем id для стабильного вывода (легче сравнивать/читать)
        Map<String, Integer> ordered = new TreeMap<>(enchants);
        StringBuilder sb = new StringBuilder();
        ordered.forEach((id, level) -> {
            if (id == null || id.isBlank() || level == null || level <= 0) return;
            if (sb.length() > 0) sb.append(';');
            sb.append(id).append(':').append(level);
        });
        return sb.toString();
    }

    /** Римские цифры для уровня (I..V). */
    public static String roman(int level) {
        return switch (Math.max(1, Math.min(10, level))) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> "I";
        };
    }
}
