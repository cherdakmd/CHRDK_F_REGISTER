package ru.cherdakmd.chrdkstreams;

import java.util.UUID;

/**
 * Заявка зрителя на просмотр канала стримера.
 *
 * <p>Пока {@code accepted == false}, зритель только подал заявку: стример
 * проверяет, что человек подписан на его каналы, и подтверждает. После
 * подтверждения зритель «смотрит» — вводит код из эфира и копит минуты.</p>
 *
 * @param uuid        зритель
 * @param name        ник зрителя на момент заявки
 * @param streamer    стример
 * @param streamerName ник стримера (для сообщений, если он оффлайн)
 * @param accepted    заявка подтверждена стримером
 * @param requestedAt когда подана заявка
 * @param lastCheckIn последний зачтённый чек-ин; {@code 0} — ещё не было
 */
record ViewerBinding(UUID uuid, String name, UUID streamer, String streamerName,
                     boolean accepted, long requestedAt, long lastCheckIn) {

    ViewerBinding {
        name = name == null ? "" : name.trim();
        streamerName = streamerName == null ? "" : streamerName.trim();
    }

    /** Подтвердить заявку: с этого момента идёт отсчёт просмотра. */
    ViewerBinding accept(long nowMillis) {
        return new ViewerBinding(uuid, name, streamer, streamerName, true, requestedAt, nowMillis);
    }

    /** Отметить чек-ин. */
    ViewerBinding checkedIn(long nowMillis) {
        return new ViewerBinding(uuid, name, streamer, streamerName, accepted, requestedAt, nowMillis);
    }

    ViewerBinding renamed(String newName) {
        return new ViewerBinding(uuid, newName, streamer, streamerName, accepted, requestedAt, lastCheckIn);
    }

    ViewerBinding moveTo(UUID newStreamer, String newStreamerName, boolean acceptedNow, long nowMillis) {
        return new ViewerBinding(uuid, name, newStreamer, newStreamerName, acceptedNow,
                nowMillis, acceptedNow ? nowMillis : 0L);
    }
}
