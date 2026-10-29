package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/** Чистый кошелёк preview. Production не выдаёт предметы: GuildFinance публикует WAL/entitlement. */
@FunctionalInterface
public interface GuildPurse {

    /**
     * Забирает до {@code amount} алмазного эквивалента у игрока.
     *
     * @return сколько удалось забрать (0 — не хватает)
     */
    long take(UUID player, long amount);

    /**
     * Выдаёт алмазы игроку.
     *
     * @return сколько не принято; preview возвращает 0, выплата публикуется отдельно
     */
    default long give(UUID player, long amount) {
        return 0L;
    }
}
