package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import java.util.Set;

/** Совместимость старых пользовательских messages.yml без перезаписи владельческих файлов. */
final class GameplayRewardTexts {
    private static final Set<String> MODULES = Set.of("chrdk_achievements", "chrdk_artifacts", "chrdk_battlepass",
            "chrdk_dailyrewards", "chrdk_orders", "chrdk_tierforge");
    private GameplayRewardTexts() { }

    static String reward(String text) {
        if (text == null) return "";
        String result = text.replaceAll("(?iu)(часами|часы|часов|часа|час|ч\\.|дни|днями|дней|дня|день|сутки|суток) ранга", "$1 буста")
                .replaceAll("(?iu)(часами|часы|часов|часа|час|ч\\.|дни|днями|дней|дня|день|сутки|суток) проходки", "$1 буста");
        result = result.replace("<gray>до</gray> <aqua>%until%</aqua>", "<gray>• только онлайн; запас — /profile</gray>");
        // В старых локалях дата окончания награды больше не имеет смысла.
        if (result.contains("%until%") && (result.contains("буста") || result.contains("Буст"))) {
            result = result.replace("%until%", "смотрите /profile");
        }
        return result;
    }

    static String message(String module, String key, String text) {
        // Старый пользовательский файл не переписываем, но снятый источник не рекламируем.
        if ("CHRDK_Core".equalsIgnoreCase(module) && "labels.usage".equals(key) && text != null)
            return text.replaceAll("(?i)\\|(?:treasures|streams)(?=\\||\\])", "")
                    .replaceAll("(?i)(\\[)(?:treasures|streams)\\|", "$1")
                    .replaceAll("(?i)\\[(?:treasures|streams)\\]", "[]");
        if (MODULES.contains(module.toLowerCase(Locale.ROOT))) return reward(text);
        return text;
    }

    static String announcement(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("приветственный ранг")) {
            return "<gray>За достижения выдаётся отдельный буст +10% опыта Minecraft, профессий и навыков. Время только онлайн; запас — /profile. Платные ранги и товары не продлеваются.</gray>";
        }
        if (lower.contains("коллекц") || lower.contains("достижен") || lower.contains("наград")) return reward(text);
        return text;
    }
}
