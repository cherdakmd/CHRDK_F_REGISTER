package ru.cherdakmd.chrdkpets;

import java.util.List;
import java.util.UUID;

/**
 * Автоматическая кличка для питомца, которого игрок так и не назвал.
 *
 * <p>На третьем уровне безымянный питомец получает имя из пула конфига.
 * Выбор детерминирован: он считается от UUID сущности, поэтому одна и та же
 * кошка всегда получает одно и то же имя, а после перезапуска кличка не
 * «прыгает». Это не замена ручному имени — игрок может переименовать питомца
 * в любой момент.</p>
 */
public final class PetNicknames {

    private PetNicknames() {
    }

    /**
     * Имя из пула по UUID. Пустой пул или битое имя — пустая строка:
     * вызывающий код тогда просто оставляет питомца безымянным.
     */
    public static String suggest(List<String> pool, UUID id) {
        if (pool == null || pool.isEmpty()) {
            return "";
        }
        int hash = id == null ? 0 : id.hashCode();
        int index = Math.floorMod(hash, pool.size());
        String name = pool.get(index);
        return name == null ? "" : name.trim();
    }
}
