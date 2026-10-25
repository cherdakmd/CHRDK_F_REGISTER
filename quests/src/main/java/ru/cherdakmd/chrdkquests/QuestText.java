package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Locale;

/**
 * Названия предметов и мобов для текстов заданий (1.4.161).
 *
 * <p>Имена берутся из языкового тега клиента ({@code <lang:block.minecraft.
 * iron_ore>}) — так русский игрок видит «Железная руда», а англичанин
 * «Iron Ore», и в конфиге не нужно держать таблицу переводов. Если тег
 * недоступен, остаётся техническое имя: лучше показать {@code iron ore}, чем
 * ничего.</p>
 */
final class QuestText {

    private QuestText() {
    }

    /** Тег имени моба. */
    static String entityTag(EntityType type) {
        if (type == null) {
            return "?";
        }
        return langTag(keyOf(() -> type.translationKey()), type.name());
    }

    /** Тег имени предмета или блока. */
    static String materialTag(Material material) {
        if (material == null || material == Material.AIR) {
            return "?";
        }
        return langTag(keyOf(() -> material.translationKey()), material.name());
    }

    /** Теги через запятую — для строки «что нужно сделать». */
    static String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? "?" : String.join(", ", tags);
    }

    /** Имя материала для лога и админских сводок. */
    static String plainMaterial(Material material) {
        return material == null ? "?" : material.name().toLowerCase(Locale.ROOT);
    }

    private static String keyOf(KeySource source) {
        try {
            String key = source.key();
            return key == null || key.isBlank() ? null : key;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static String langTag(String key, String fallbackName) {
        if (key == null) {
            return fallbackName.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return "<lang:" + key + ">";
    }

    /** Лямбда без лишних аллокаций: тег запрашивается у Bukkit-типа. */
    @FunctionalInterface
    private interface KeySource {
        String key();
    }
}
