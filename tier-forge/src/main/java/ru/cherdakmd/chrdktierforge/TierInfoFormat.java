package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Set;

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
     * Материалы, у которых перевод блочный ({@code block.minecraft.*}), хотя
     * они и лежат в инвентаре предметом. Список закрытый и совпадает с
     * утилитой {@link ItemKind} (1.4.142): подсолнух, фонарь душ, шипы и череп
     * ставятся в мир, поэтому их имя в клиенте берётся из блока.
     */
    private static final Set<String> BLOCK_ITEMS = Set.of(
            "SUNFLOWER", "SOUL_LANTERN", "POINTED_DRIPSTONE", "WITHER_SKELETON_SKULL");

    /**
     * Ключ перевода материала: {@code item.minecraft.diamond_sword} для
     * обычных предметов и {@code block.minecraft.soul_lantern} для тех, что
     * ставятся в мир.
     *
     * <p>Проверять {@code Material#isBlock()} здесь нельзя: на Paper он лезет
     * в {@code Registry} и требует живого сервера, а этот класс обязан
     * оставаться чистым (юнит-тесты). Поэтому блочные материалы перечислены
     * явно — их немного, и все они входят в роль утилиты.</p>
     */
    static String translationKey(Material material) {
        String path = material.name().toLowerCase(Locale.ROOT);
        return (BLOCK_ITEMS.contains(material.name()) ? "block.minecraft." : "item.minecraft.")
                + path;
    }

    /**
     * Тег языка клиента для материала: {@code <lang:item.minecraft.diamond_sword>}.
     *
     * <p>Пространство имён — {@code item.*} у обычных предметов и {@code block.*}
     * у тех, что ставятся в мир (см. {@link #translationKey(Material)});
     * катализатор переката по умолчанию тоже предмет.</p>
     *
     * <p>Пустой материал и воздух — пустая строка (линию просто не показываем).
     */
    static String languageTag(Material material) {
        if (material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return "";
        }
        return "<lang:" + translationKey(material) + ">";
    }
}
