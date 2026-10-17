package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Отзыв проходки администратором ({@code /pass remove}). Генерируется на главном потоке.
 * Истечение срока — это другое событие: {@link PassExpiredEvent}.
 */
public final class PassRevokedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;

    public PassRevokedEvent(String playerName) {
        this.playerName = playerName;
    }

    /** Ник игрока, у которого отозвали проходку (может быть оффлайн). */
    public String getPlayerName() {
        return playerName;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
