package ru.cherdakmd.chrdkhunt;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;

/** Отдельная страница существующего бестиария: не изменяет старые призы за разделы. */
final class WildBestiaryGui implements Listener {
    private static final int[] SLOTS = {10, 12, 14, 19, 21, 23};
    private static final class Holder implements InventoryHolder {
        final UUID owner; Inventory inventory;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final HuntPlugin plugin;
    WildBestiaryGui(HuntPlugin plugin) { this.plugin = plugin; }
    boolean open(Player p) {
        if (!plugin.finance().eligible(p)) { plugin.messages().send(p, "finance-mode"); return false; }
        if (!plugin.config().enabled()) { plugin.messages().send(p, "disabled"); return false; }
        plugin.wildBiomes().inspect(p);
        Holder holder = new Holder(p.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 36, plugin.messages().get("wild-title"));
        int index = 0;
        for (var v : WildVariant.values()) {
            long kills = plugin.store().wildKills(p.getUniqueId(), v.id);
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("wild-habitat-" + v.id));
            lore.add(plugin.messages().get("wild-counter-" + v.id));
            lore.add(plugin.messages().get(plugin.store().wildSeen(p.getUniqueId(), v.id) ? "wild-seen" : "wild-unseen"));
            lore.add(plugin.messages().get("wild-kills", "kills", kills));
            lore.add(plugin.messages().get(kills > 0 ? "wild-trophy-owned" : "wild-trophy-locked"));
            holder.inventory.setItem(SLOTS[index++], plugin.items().icon(v.icon, plugin.messages().get("wild-name-" + v.id), lore));
        }
        holder.inventory.setItem(4, plugin.items().icon(Material.BOOK, plugin.messages().get("wild-info"), plugin.messages().getList("wild-info-lore")));
        holder.inventory.setItem(31, plugin.items().icon(Material.CLOCK, plugin.messages().get("moon-title"), List.of(
                plugin.messages().get("moon-status-" + plugin.fullMoon().status(p.getWorld())),
                plugin.messages().get("moon-limits", "count", plugin.fullMoon().count()), plugin.messages().get("moon-rules"))));
        holder.inventory.setItem(27, plugin.items().icon(Material.ARROW, plugin.messages().get("wild-back"), List.of()));
        holder.inventory.setItem(35, plugin.items().icon(Material.BARRIER, plugin.messages().get("menu-close"), List.of()));
        p.openInventory(holder.inventory); return true;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId())) return;
        if (e.getRawSlot() == 35) p.closeInventory();
        if (e.getRawSlot() == 27 || e.getRawSlot() == 31) {
            boolean back = e.getRawSlot() == 27;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != h || !plugin.finance().eligible(p)) return;
                if (back) plugin.bestiaryGui().open(p); else open(p);
            });
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    void closeAll() { for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory(); }
}
