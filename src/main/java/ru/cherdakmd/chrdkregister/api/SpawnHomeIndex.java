package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/**
 * Мост «дома → ядро» для случайного спавна (1.4.106). Реализацию регистрирует
 * <b>CHRDK_Teleports</b> через {@code ServicesManager}; ядро спрашивает её, чтобы не
 * переносить в случайную точку игрока, у которого уже есть дом ({@code /sethome}).
 *
 * <p>Если аддона нет, проверка просто пропускается.</p>
 */
public interface SpawnHomeIndex {

    /** Есть ли у игрока хотя бы одна сохранённая точка дома. */
    boolean hasHome(UUID playerId);
}
