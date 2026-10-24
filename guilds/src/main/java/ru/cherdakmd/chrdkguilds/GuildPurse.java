package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/**
 * Кошелёк гильдейской казны (1.4.163).
 *
 * <p>Экономика сервера — алмазы предметами, но сервису гильдий не нужно знать
 * про инвентарь: он просит «взять столько» и «выдать столько». В игре это
 * реализует {@link DiamondPurse}, в тестах — простая заглушка. Так правила
 * казны проверяются без живого сервера, а игрок не может уйти в минус.</p>
 */
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
     * @return сколько не поместилось (выпало под ноги); в штатной ситуации 0
     */
    default long give(UUID player, long amount) {
        return 0L;
    }
}
