package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Чистое форматирование для {@code /tiers info} (1.4.84): ключ сообщения об
 * источнике происхождения и языковой тег материала. Без Bukkit-сервера —
 * покрыто {@link TierInfoFormatTest}.
 */
final class TierInfoFormat {

    private TierInfoFormat() {
    }

    /** Ключ messages.yml для источника PDC ("craft"/"drop"/"give"/…). */
    static String sourceKey(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "craft" -> "source-craft";
            case "drop" -> "source-drop";
            case "give" -> "source-give";
            default -> "source-unknown";
        };
    }

    /**
     * Тег языка клиента для материала: {@code <lang:item.minecraft.diamond_sword>}.
     * Пространство имён выбирается по {@link Material#isBlock()}: у блоков
     * перевод живёт в {@code block.minecraft.*}, у предметов — в
     * {@code item.minecraft.*} (у камня есть предметная форма, но ключ — блочный).
     * Пустой материал/воздух — пустая строка (линию просто не показываем).
     */
    static String languageTag(Material material) {
        if (material == null || material.isAir()) {
            return "";
        }
        String path = material.name().toLowerCase(Locale.ROOT);
        return "<lang:" + (material.isBlock() ? "block" : "item") + ".minecraft." + path + ">";
    }
}
