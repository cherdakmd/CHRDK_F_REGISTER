package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Поиск материала по имени из {@code config.yml} (1.4.160).
 *
 * <p>Как и в остальных аддонах репозитория, здесь {@link Material#valueOf(String)},
 * а не {@code matchMaterial}: последний в современных версиях идёт через
 * реестр, который поднимается только на живом сервере, и разбор конфига нельзя
 * было бы проверить тестом. Для ванильных имён результат одинаковый, а
 * опечатка так же возвращает {@code null}.</p>
 */
final class EventMaterials {

    private static final String NAMESPACE = "minecraft:";

    private EventMaterials() {
    }

    /** Материал по имени или {@code null}, если такого в игре нет. */
    static Material resolve(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(NAMESPACE)) {
            key = key.substring(NAMESPACE.length()).trim();
        }
        if (key.isEmpty()) {
            return null;
        }
        try {
            return Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Материал, который вообще может лежать в инвентаре: не воздух. */
    static boolean isItem(Material material) {
        return material != null && material != Material.AIR && material != Material.CAVE_AIR
                && material != Material.VOID_AIR;
    }
}
