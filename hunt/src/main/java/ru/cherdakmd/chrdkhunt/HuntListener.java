package ru.cherdakmd.chrdkhunt;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Слушает смерть мобов и передаёт добычу в {@link HuntService} (1.4.155).
 *
 * <p>Событие берётся на {@code MONITOR}: другие плагины могут отменить или
 * изменить смерть, и охоте нужен уже итоговый факт. Награда начисляется только
 * при наличии убийцы-игрока: моб, упавший в лаву сам по себе, ничего не даёт.</p>
 */
public final class HuntListener implements Listener {

    private final HuntPlugin plugin;

    HuntListener(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead == null) {
            return;
        }
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }
        plugin.service().handleDeath(killer, dead);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            plugin.service().forget(event.getPlayer().getUniqueId());
        }
    }
}
