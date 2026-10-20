package ru.cherdakmd.chrdkstreams;

import java.util.UUID;

/**
 * Привязка игрока к каналу и его прогресс.
 *
 * <p>Одна запись на игрока: канал, код подтверждения и накопленный эфир. Поля
 * {@code day}, {@code awardedToday}, {@code pendingMillis}, {@code totalDays}
 * меняются каждым опросом — поэтому запись неизменяемая, а сервис заменяет её
 * целиком через {@code with...} методы: так нет «наполовину обновлённой» записи
 * и легко проверять арифметику в тестах.</p>
 *
 * @param uuid         игрок
 * @param playerName   ник на момент привязки (для логов и {@code /stream stats})
 * @param platform     площадка
 * @param channel      имя канала в том виде, как его ввёл игрок
 * @param code         код подтверждения ({@code CHRDK-XXXX})
 * @param verifiedAt   когда канал подтверждён; {@code 0} — ещё нет
 * @param state        накопленный прогресс наград
 */
record StreamBinding(UUID uuid, String playerName, StreamPlatform platform, String channel,
                     String code, long verifiedAt, StreamRewards.State state) {

    StreamBinding {
        playerName = playerName == null ? "" : playerName.trim();
        channel = channel == null ? "" : channel.trim();
        code = code == null ? "" : code.trim();
        state = state == null ? StreamRewards.State.empty() : state;
    }

    /** Канал владельца подтверждён кодом в названии эфира. */
    boolean verified() {
        return verifiedAt > 0;
    }

    /** Ссылка на канал для сообщений. */
    String url() {
        return platform.urlFor(channel);
    }

    /** Пометить канал подтверждённым (код найден в названии эфира). */
    StreamBinding markVerified(long nowMillis) {
        return new StreamBinding(uuid, playerName, platform, channel, code,
                nowMillis, state);
    }

    StreamBinding withState(StreamRewards.State newState) {
        return new StreamBinding(uuid, playerName, platform, channel, code, verifiedAt, newState);
    }

    StreamBinding renamed(String newName) {
        return new StreamBinding(uuid, newName, platform, channel, code, verifiedAt, state);
    }
}
