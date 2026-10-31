package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Ленивое обновление без новых бросков; события объединяются до следующего тика. */
public final class TierRefreshListener implements Listener {
    private final TierForgePlugin plugin;
    private final Set<UUID> pending = new LinkedHashSet<>();
    private BukkitTask task;

    public TierRefreshListener(TierForgePlugin plugin) { this.plugin = plugin; }

    private void schedule(Player player) {
        pending.add(player.getUniqueId());
        if (task != null) return;
        task = Bukkit.getScheduler().runTask(plugin, () -> {
            task = null;
            Set<UUID> batch = Set.copyOf(pending);
            pending.clear();
            for (UUID id : batch) {
                Player current = Bukkit.getPlayer(id);
                if (current != null) refreshAll(current);
            }
        });
    }

    void close() {
        if (task != null) task.cancel();
        task = null;
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) { schedule(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) schedule(player);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }

    /** Обновить все слоты с явной записью изменённых стеков обратно в инвентарь. */
    void refreshAll(Player player) {
        if (!player.isOnline() || player.isDead()) return;
        plugin.invalidateForgeGear(player.getUniqueId());
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (plugin.items().refreshTierModifiers(contents[slot])) {
                inventory.setItem(slot, contents[slot]);
            }
        }
    }
}
