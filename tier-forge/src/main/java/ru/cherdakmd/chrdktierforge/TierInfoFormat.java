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
     *
     * <p>Пространство имён всегда {@code item.*}: тир-предметы — это броня, оружие
     * и инструменты, у которых перевод предметный; катализатор переката по
     * умолчанию тоже предмет. Проверять {@code Material#isBlock()} здесь нельзя:
     * на Paper он лезет в {@code Registry} и требует живого сервера, а этот класс
     * обязан оставаться чистым (юнит-тесты).
     *
     * <p>Пустой материал и воздух — пустая строка (линию просто не показываем).
     */
    static String languageTag(Material material) {
        if (material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return "";
        }
        String path = material.name().toLowerCase(Locale.ROOT);
        return "<lang:item.minecraft." + path + ">";
    }
}
