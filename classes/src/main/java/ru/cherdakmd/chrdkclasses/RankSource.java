package ru.cherdakmd.chrdkclasses;

import java.util.UUID;

/**
 * Источник ступени донатного ранга для плюшек классов (1.4.175).
 *
 * <p>Отдельный интерфейс по той же причине, что {@link SkillSource}: ядро —
 * необязательная зависимость с точки зрения тестов, а {@link TalentService}
 * должен проверяться без Bukkit. Ранг 0 (или отсутствие ядра) означает
 * {@link RankPerks#NONE} — базовые правила без скидок.</p>
 */
public interface RankSource {

    /** Доступны ли данные о рангах (ядро с API v9+ и ранги включены). */
    boolean available();

    /** Ступень действующего ранга: 0 — ранга нет. */
    int rankLevel(UUID player);
    /** Строгое чтение для отчётов и переноса, без подмены ошибки нулём. */
    default ClassDataRead snapshotRank(UUID player) {
        return ClassDataRead.read(this::available, () -> rankLevel(player)).atMost(5);
    }
}
