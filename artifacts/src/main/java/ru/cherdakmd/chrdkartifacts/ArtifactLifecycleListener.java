package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Пересчёт на следующем тике, после фактической смены инвентаря/возрождения.
 * Не активирует артефакты и не ускоряет случайные эффекты частыми кликами.
 * Штатный пульс остаётся страховкой для команд и изменений сторонних плагинов.
 */
final class ArtifactLifecycleListener implements Listener {
    private final ArtifactsPlugin plugin;
    private final PassiveEffectsService passives;
    private final ArtifactRefreshQueue pending = new ArtifactRefreshQueue();
    private BukkitTask task;

    ArtifactLifecycleListener(ArtifactsPlugin plugin, PassiveEffectsService passives) {
        this.plugin = plugin;
        this.passives = passives;
    }

    private void schedule(Player player) {
        pending.request(player.getUniqueId());
        if (task != null) return;
        task = Bukkit.getScheduler().runTask(plugin, () -> {
            task = null;
            for (var id : pending.drain()) {
                Player current = Bukkit.getPlayer(id);
                if (current != null && current.isOnline()) passives.refreshAttributes(current);
            }
        });
    }

    void close() {
        if (task != null) task.cancel();
        task = null;
        pending.drain();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        pending.forget(event.getEntity().getUniqueId());
        passives.clearAttributes(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.forget(event.getPlayer().getUniqueId());
        passives.clearAttributes(event.getPlayer());
    }
}
