package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import java.util.Map;

/** Наблюдает только собственный фактический инвентарь после native commit, без выдачи/расхода. */
final class EnchantLibraryListener implements Listener {
    private final EnchantsPlugin plugin;
    private final NamespacedKey index;
    private final MiningTasks pending;
    EnchantLibraryListener(EnchantsPlugin plugin) {
        this.plugin = plugin; index = new NamespacedKey(plugin, "library_v1");
        pending = new MiningTasks(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
    }
    void clear() { pending.clear(); }
    private boolean enabled(Player player) {
        return plugin.getConfig().getBoolean("library.enabled", true) && player.isOnline() && !player.isDead()
                && player.getGameMode() == GameMode.SURVIVAL && player.hasPermission("chrdkenchants.use")
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    private Map<String, Integer> read(Player player) {
        var data = player.getPersistentDataContainer();
        if (!data.has(index)) return Map.of();
        if (!data.has(index, PersistentDataType.STRING)) return null;
        String raw = data.get(index, PersistentDataType.STRING);
        return raw == null ? null : EnchantLibrary.read(raw);
    }
    void queue(Player player) {
        if (!enabled(player)) return;
        long generation = plugin.sourceGeneration(); var world = player.getWorld().getUID();
        pending.submit(player.getUniqueId(), player.getUniqueId(), () -> {
            if (generation != plugin.sourceGeneration() || !enabled(player) || !world.equals(player.getWorld().getUID())) return;
            scan(player);
        });
    }
    private void scan(Player player) {
        Map<String, Integer> before = read(player), found = before;
        if (before == null) return; // Повреждённые/будущие данные не затираются автосканированием.
        for (var item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.ENCHANTED_BOOK || item.getAmount() <= 0
                    || item.getAmount() > item.getMaxStackSize() || !plugin.items().marked(item)) continue;
            var book = plugin.items().validated(item);
            if (book != null) found = EnchantLibrary.merge(found, book);
        }
        if (found != null && !before.equals(found))
            player.getPersistentDataContainer().set(index, PersistentDataType.STRING, EnchantLibrary.encode(found));
    }
    /** Команда только читает уже обнаруженное, не сканирует и не пишет PDC. */
    void show(Player player, int requested) {
        if (!plugin.getConfig().getBoolean("library.enabled", true)) { plugin.messages().send(player, "library-disabled"); return; }
        var entries = read(player);
        if (entries == null) { plugin.messages().send(player, "library-invalid"); return; }
        int page = Math.clamp((long) requested, 1, EnchantLibrary.pages(entries.size()));
        plugin.messages().send(player, "library-header", "count", entries.size(), "total", EnchantRegistry.size(),
                "page", page, "pages", EnchantLibrary.pages(entries.size()));
        if (entries.isEmpty()) { plugin.messages().send(player, "library-empty"); return; }
        for (var entry : EnchantLibrary.page(entries, page)) {
            var def = EnchantRegistry.byId(entry.getKey());
            plugin.messages().send(player, "library-line", "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "level", EnchantCodec.roman(entry.getValue()), "max", EnchantCodec.roman(def.maxLevel()),
                    "state", plugin.messages().mini(plugin.config().isEnabled(def.id()) ? "catalog-on" : "catalog-off"));
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
    @EventHandler public void auth(PlayerAuthCompletedEvent event) { queue(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.cancel(event.getPlayer().getUniqueId()); }
    @EventHandler public void world(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        pending.cancel(event.getPlayer().getUniqueId()); queue(event.getPlayer());
    }
    @EventHandler public void death(org.bukkit.event.entity.PlayerDeathEvent event) { pending.cancel(event.getEntity().getUniqueId()); }
}
