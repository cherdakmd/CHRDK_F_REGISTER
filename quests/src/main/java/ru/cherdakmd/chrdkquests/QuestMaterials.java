package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;

/**
 * Разбор имён материалов и мобов из конфига (1.4.161).
 *
 * <p>Как и в остальных аддонах, здесь {@code valueOf}, а не
 * {@code matchMaterial}: последний в современных версиях идёт через реестр,
 * который поднимается только на живом сервере, и разбор конфига нельзя было бы
 * проверить тестом. Namespace {@code minecraft:} снимается — в конфиге можно
 * писать и {@code IRON_ORE}, и {@code minecraft:iron_ore}.</p>
 */
final class QuestMaterials {

    private static final String NAMESPACE = "minecraft:";

    private QuestMaterials() {
    }

    /** Материал по имени или {@code null}, если такого нет. */
    static Material material(String name) {
        String key = key(name);
        if (key == null) {
            return null;
        }
        try {
            return Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Тип моба по имени или {@code null}, если такого нет. */
    static EntityType entity(String name) {
        String key = key(name);
        if (key == null) {
            return null;
        }
        try {
            return EntityType.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Материал, который может лежать в инвентаре. */
    static boolean isItem(Material material) {
        return material != null && material != Material.AIR && material != Material.CAVE_AIR
                && material != Material.VOID_AIR;
    }

    private static String key(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(NAMESPACE)) {
            key = key.substring(NAMESPACE.length()).trim();
        }
        return key.isEmpty() ? null : key;
    }
}
