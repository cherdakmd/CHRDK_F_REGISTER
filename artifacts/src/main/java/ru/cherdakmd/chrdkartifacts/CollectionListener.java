package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Точки открытия коллекции (1.4.130).
 *
 * <p>Основной путь — подбор предмета с земли: он одинаково работает для
 * находки в сундуке, дропа с босса, рыбалки и подарка от другого игрока.
 * При входе и выходе добавляются служебные действия: скан инвентаря
 * (предметы, полученные до 1.4.130) и очистка троттлинга проклятий.</p>
 */
public final class CollectionListener implements Listener {

    private final ArtifactsPlugin plugin;

    public CollectionListener(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String id = plugin.items().idOf(event.getItem().getItemStack());
        if (id != null) {
            plugin.collection().record(player, id);
        }
    }

    /**
     * Скан на входе — с задержкой: в момент события инвентарь ещё не догружен
     * клиентом, а сообщения лучше отправлять, когда игрок уже в мире.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.collection().scan(player);
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.feedback() != null) {
            plugin.feedback().forget(event.getPlayer().getUniqueId());
        }
    }
}
