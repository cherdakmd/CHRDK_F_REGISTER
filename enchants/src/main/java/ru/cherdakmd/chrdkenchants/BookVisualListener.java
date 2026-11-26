package ru.cherdakmd.chrdkenchants;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/** Bounded, next-tick visual refresh of the player's storage only, never open containers or cursor. */
final class BookVisualListener implements Listener {
    private final EnchantsPlugin plugin;
    private final MiningTasks pending;
    private final java.util.function.Predicate<ItemStack> update;
    private final java.util.function.Predicate<Player> safeView;
    BookVisualListener(EnchantsPlugin plugin) {
        this(plugin, item -> plugin.items().refreshBookVisual(item),
                player -> player.getOpenInventory().getType() == InventoryType.CRAFTING);
    }
    BookVisualListener(EnchantsPlugin plugin, java.util.function.Predicate<ItemStack> update,
                       java.util.function.Predicate<Player> safeView) {
        this.plugin = plugin;
        this.update = java.util.Objects.requireNonNull(update);
        this.safeView = java.util.Objects.requireNonNull(safeView);
        pending = new MiningTasks(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
    }
    void clear() { pending.clear(); }
    void queue(Player player) {
        if (!plugin.getConfig().getBoolean("textures.books", false) || !player.isOnline() || player.isDead()) return;
        UUID id = player.getUniqueId(), world = player.getWorld().getUID();
        long generation = plugin.sourceGeneration();
        pending.submit(id, id, () -> {
            if (!plugin.isEnabled() || generation != plugin.sourceGeneration() || !player.isOnline() || player.isDead()
                    || !plugin.getConfig().getBoolean("textures.books", false) || !world.equals(player.getWorld().getUID())
                    || player.getGameMode() == GameMode.CREATIVE || !safeView.test(player)) return;
            scan(player);
        });
    }
    private void scan(Player player) {
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack before = contents[slot];
            if (before == null) continue;
            ItemStack copy = before.clone();
            if (update.test(copy) && before.equals(inventory.getItem(slot))) inventory.setItem(slot, copy);
        }
        ItemStack before = inventory.getItemInOffHand();
        if (before != null) {
            ItemStack copy = before.clone();
            if (update.test(copy) && before.equals(inventory.getItemInOffHand())) inventory.setItemInOffHand(copy);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player player) queue(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player) queue(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player) queue(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { queue(event.getPlayer()); }
    @EventHandler public void join(PlayerJoinEvent event) { queue(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.cancel(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent event) { pending.cancel(event.getEntity().getUniqueId()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) { pending.cancel(event.getPlayer().getUniqueId()); queue(event.getPlayer()); }
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) queue(player);
    }
}
