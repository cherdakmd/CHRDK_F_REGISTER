package ru.cherdakmd.chrdkclasses;

import java.util.UUID;

/**
 * Источник открытых достижений для очков талантов (1.4.175).
 *
 * <p>Отдельный интерфейс, а не прямой вызов {@code CHRDK_Achievements}: аддон
 * достижений необязателен, а {@link TalentService} должен оставаться чистым и
 * проверяемым без сервера. Без достижений бонус просто ноль — правила классов
 * от этого не меняются.</p>
 */
public interface AchievementSource {

    /** Есть ли аддон достижений и отдаёт ли он данные. */
    boolean available();

    /** Сколько достижений открыто у игрока (0, если данных нет). */
    int unlockedCount(UUID player);
    /** Строгое чтение для отчётов и переноса, без подмены ошибки нулём. */
    default ClassDataRead snapshotCount(UUID player) {
        return ClassDataRead.read(this::available, () -> unlockedCount(player));
    }
}
