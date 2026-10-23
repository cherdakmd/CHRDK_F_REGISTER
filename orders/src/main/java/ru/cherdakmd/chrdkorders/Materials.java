package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Поиск материала по имени из конфига и {@code orders.json}.
 *
 * <p>{@link Material#matchMaterial(String)} в современных версиях Paper идёт
 * через {@code Registry.MATERIAL}, а реестр поднимается только на живом сервере.
 * Из-за этого разбор конфига нельзя было проверить обычным unit-тестом — JVM
 * падала на инициализации {@code org.bukkit.Registry}. Здесь тот же результат
 * для всех ванильных имён даёт {@link Material#valueOf(String)}: он работает и
 * на сервере, и в тестах, а опечатка так же возвращает {@code null}.</p>
 *
 * <p>Принимаются имена в любом регистре, с префиксом {@code minecraft:} или без
 * него. Легаси-имена и числовые id не поддерживаются намеренно: в конфиге
 * доски заказов им взяться неоткуда.</p>
 */
final class Materials {

    private static final String NAMESPACE = "minecraft:";

    private Materials() {
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
}
