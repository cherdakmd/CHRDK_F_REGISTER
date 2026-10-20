package ru.cherdakmd.chrdkstreams;

import java.io.IOException;

/**
 * Клиент площадки: спрашивает, идёт ли эфир у канала.
 *
 * <p>Реализации обязаны быть терпимыми: недоступный API — это {@link IOException},
 * который сервис логирует и пропускает опрос, а не падение плагина. Ответ, который
 * не удалось разобрать, считается «эфира нет».</p>
 */
interface StreamClient {

    StreamPlatform platform();

    /**
     * Запрос к площадке. Вызывать только вне главного потока.
     *
     * @param channel имя канала (уже проверенное {@link StreamPlatform#validChannel})
     * @throws IOException площадка недоступна, нет токена или получен отказ
     */
    LiveStream fetch(String channel) throws IOException, InterruptedException;
}
