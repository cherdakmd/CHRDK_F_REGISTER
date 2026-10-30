package ru.cherdakmd.chrdkclasses;

import java.util.UUID;

/**
 * Источник уровней навыков (1.4.164).
 *
 * <p>Классы не дублируют прокачку: опыт живёт в {@code CHRDK_Skills}, а класс
 * только читает его через публичный {@code ChrdkSkillsApi}. Интерфейс отдельный,
 * потому что аддон навыков необязателен: без него класс работает на пассивной
 * основе и нулевых уровнях, а требования талантов честно не выполняются.</p>
 */
public interface SkillSource {

    /** Доступны ли навыки: {@code false} — плагина нет или он выключен. */
    boolean available();

    /** Уровень навыка игрока (0, если навыка нет или он не качался). */
    int level(UUID player, String skillId);

    /** Сумма уровней всех навыков — из неё выводится уровень класса. */
    int totalLevel(UUID player);
    /** Строгое чтение для отчётов и переноса, без подмены ошибки нулём. */
    default ClassDataRead snapshotTotal(UUID player) {
        return ClassDataRead.read(this::available, () -> totalLevel(player));
    }
    /** Строгое чтение конкретного требования: недоступный навык не становится нулём. */
    default ClassDataRead snapshotLevel(UUID player, String skillId) {
        if (player == null || skillId == null || skillId.isBlank()) {
            return ClassDataRead.absent(ClassDataRead.Status.INVALID);
        }
        return ClassDataRead.read(this::available, () -> level(player, skillId));
    }
}
