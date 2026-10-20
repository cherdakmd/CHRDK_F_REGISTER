package ru.cherdakmd.chrdkstreams;

import java.util.UUID;

/**
 * Зритель, привязанный к стримеру.
 *
 * <p>Здесь только отношения «зритель ↔ стример» и отметки времени. Прогресс
 * (накопленные минуты и выданные сутки) хранится отдельно, в
 * {@link ViewerStore} на зрителя: поэтому отписка, смена канала или повторная
 * заявка не обнуляют дневной потолок.</p>
 *
 * @param uuid         зритель
 * @param playerName   ник на момент заявки/чек-ина
 * @param streamer     стример, к которому привязан зритель
 * @param streamerName ник стримера (для сообщений, когда он оффлайн)
 * @param accepted     стример подтвердил, что зритель подписан на его каналы
 * @param requestedAt  когда подана заявка
 * @param lastCheckIn  когда последний раз засчитан код; {@code 0} — ещё ни разу
 */
record ViewerBinding(UUID uuid, String playerName, UUID streamer, String streamerName,
                     boolean accepted, long requestedAt, long lastCheckIn) {

    ViewerBinding {
        playerName = playerName == null ? "" : playerName.trim();
        streamerName = streamerName == null ? "" : streamerName.trim();
    }

    /**
     * Подтвердить заявку. Отметка чек-ина остаётся нулевой: подтверждение — это
     * проверка подписки, а не просмотр. Первый код запускает счёт (0 минут),
     * поэтому время до него не засчитывается.
     */
    ViewerBinding accept() {
        return new ViewerBinding(uuid, playerName, streamer, streamerName, true,
                requestedAt, 0L);
    }

    /** Отметить чек-ин: следующий отрезок считается от этого момента. */
    ViewerBinding checkedIn(long nowMillis) {
        return new ViewerBinding(uuid, playerName, streamer, streamerName, accepted,
                requestedAt, nowMillis);
    }

    /** Обновить ник (игрок мог сменить его). */
    ViewerBinding renamed(String newName) {
        return new ViewerBinding(uuid, newName, streamer, streamerName, accepted,
                requestedAt, lastCheckIn);
    }

    /**
     * Перейти к другому стримеру. Заявка снова ждёт подтверждения, если
     * {@code acceptedNow == false}; прогресс при этом не трогается — он живёт
     * отдельно и принадлежит зрителю.
     */
    ViewerBinding moveTo(UUID newStreamer, String newStreamerName, boolean acceptedNow,
                         long nowMillis) {
        return new ViewerBinding(uuid, playerName, newStreamer, newStreamerName,
                acceptedNow, nowMillis, 0L);
    }
}
