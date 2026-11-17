package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Перерождение персонажа завершено (п.30 навыков): уровни, атрибуты и ветки
 * сброшены, счётчик перерождений увеличен. Вызывается на главном потоке.
 *
 * <p>Аддоны, чьи системы тоже перерождаются (генератор ресурсов — тиры и
 * модули, п.31), слушают это событие и выполняют свой сброс. Мягкая
 * связка: без навыков событие просто не возникает.</p>
 */
public final class RebirthEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int count;

    public RebirthEvent(Player player, int count) {
        this.player = player;
        this.count = count;
    }

    /** Персонаж, который переродился (онлайн). */
    public Player getPlayer() {
        return player;
    }

    /** Сколько перерождений уже сделано (1 — первое). */
    public int getCount() {
        return count;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
