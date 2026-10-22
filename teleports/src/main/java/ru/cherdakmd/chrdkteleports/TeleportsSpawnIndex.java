package ru.cherdakmd.chrdkteleports;

import ru.cherdakmd.chrdkregister.api.SpawnHomeIndex;

import java.util.UUID;

/**
 * Мост «дома → ядро» для случайного спавна (ядро 1.4.106+): игрока с сохранённым домом
 * ядро в случайную точку не переносит. Регистрируется в {@code ServicesManager};
 * при отсутствии ядра просто не используется.
 */
public final class TeleportsSpawnIndex implements SpawnHomeIndex {

    private final TeleportsPlugin plugin;

    public TeleportsSpawnIndex(TeleportsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Есть ли у игрока сохранённое место. Кровать считается: после первого сна
     * у игрока появляется дом, и ядро не должно уводить его в случайную точку.
     */
    @Override
    public boolean hasHome(UUID playerId) {
        HomeStore store = plugin.store();
        return playerId != null && store != null && store.count(playerId) > 0;
    }
}
