package ru.cherdakmd.chrdkregister;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Досылает купленные товары при входе игрока.
 *
 * <p>Покупка на витрине может случиться до первого входа: UUID ещё неизвестен, а группа
 * LuckPerms выдаётся именно на UUID. Поэтому при входе сервис повторно применяет активные
 * товары — идемпотентно: старые узлы группы заменяются одним новым.</p>
 */
public final class ShopListener implements Listener {

    private final ShopService shop;

    public ShopListener(ShopService shop) {
        this.shop = shop;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        shop.applyPending(event.getPlayer());
    }
}
