package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Истечение срока проходки. Для игрока онлайн — в момент перевода в заблокированный режим;
 * истечение у оффлайн-игрока фиксируется при его следующем входе. Главный поток.
 */
public final class PassExpiredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;

    public PassExpiredEvent(String playerName) {
        this.playerName = playerName;
    }

    /** Ник игрока, у которого истекла проходка. */
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
