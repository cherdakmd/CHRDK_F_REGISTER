package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Выдача (или продление) проходки: донат DonatePay, ручная выдача, подарок, выдача из аддона.
 * Вызывается на главном потоке сервера.
 */
public final class PassGrantedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final long passUntil;
    private final int days;
    private final String source;

    public PassGrantedEvent(String playerName, long passUntil, int days, String source) {
        this.playerName = playerName;
        this.passUntil = passUntil;
        this.days = days;
        this.source = source;
    }

    /** Ник игрока (может быть оффлайн). */
    public String getPlayerName() {
        return playerName;
    }

    /** Unix-время (мс) окончания продлённой проходки. */
    public long getPassUntil() {
        return passUntil;
    }

    /** Длительность выдачи в днях. */
    public int getDays() {
        return days;
    }

    /** Метка источника: {@code donatepay:#42}, {@code manual}, {@code gift:Nick}, имя аддона и т.д. */
    public String getSource() {
        return source;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
