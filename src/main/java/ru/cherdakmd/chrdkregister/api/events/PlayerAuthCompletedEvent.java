package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Игрок полностью завершил авторизацию (пароль + 2FA при её наличии) и переведён в
 * «играющий» режим. Удобное событие для аддонов «только для авторизованных» —
 * например, магазина или обменника. Главный поток.
 */
public final class PlayerAuthCompletedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public PlayerAuthCompletedEvent(@NotNull Player player) {
        super(player);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
