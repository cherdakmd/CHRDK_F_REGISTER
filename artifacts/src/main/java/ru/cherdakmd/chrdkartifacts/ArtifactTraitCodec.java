package ru.cherdakmd.chrdkartifacts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сериализация свойств артефакта в PDC: {@code id:level;id:level}.
 *
 * <p>Кодек чистый: реестр не трогает, мусор игнорирует. Проверка «такое свойство
 * существует и уровень допустим» — на стороне {@link ArtifactItems}.</p>
 */
public final class ArtifactTraitCodec {

    private ArtifactTraitCodec() {
    }

    /** Парсит строку в карту id→уровень (порядок сохраняется, мусор отбрасывается). */
    public static Map<String, Integer> decode(String raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String token : raw.split(";")) {
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                continue;
            }
            String id = token.substring(0, colon).trim();
            if (id.isEmpty()) {
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(token.substring(colon + 1).trim());
            } catch (NumberFormatException error) {
                continue;
            }
            if (level <= 0 || level > ArtifactTraits.hardMaxLevel()) {
                continue;
            }
            result.merge(id, level, Math::max);
        }
        return result;
    }

    /** Сериализует карту (пустые записи и нулевые уровни отбрасываются, id сортируются). */
    public static String encode(Map<String, Integer> traits) {
        if (traits == null || traits.isEmpty()) {
            return "";
        }
        Map<String, Integer> ordered = new TreeMap<>(traits);
        StringBuilder builder = new StringBuilder();
        ordered.forEach((id, level) -> {
            if (id == null || id.isBlank() || level == null || level <= 0) {
                return;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(id).append(':').append(level);
        });
        return builder.toString();
    }

    /** Строка из списка бросков (для записи в PDC сразу после выдачи). */
    public static String encode(List<ArtifactTraits.Roll> rolls) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (rolls != null) {
            for (ArtifactTraits.Roll roll : rolls) {
                map.merge(roll.id(), roll.level(), Math::max);
            }
        }
        return encode(map);
    }

    /** Римские цифры для уровня (I..III, с запасом до V). */
    public static String roman(int level) {
        return switch (Math.max(1, Math.min(5, level))) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "I";
        };
    }
}
