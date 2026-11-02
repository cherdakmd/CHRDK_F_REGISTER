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
        final UUID owner; boolean moon; Inventory inventory;
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
        holder.inventory.setItem(28, plugin.items().icon(Material.COMPASS, plugin.messages().get("menu-main-back"), List.of()));
        holder.inventory.setItem(27, plugin.items().icon(Material.ARROW, plugin.messages().get("wild-back"), List.of()));
        holder.inventory.setItem(35, plugin.items().icon(Material.BARRIER, plugin.messages().get("menu-close"), List.of()));
        p.openInventory(holder.inventory); return true;
    }
    boolean openMoon(Player p) {
        if (!plugin.finance().eligible(p)) { plugin.messages().send(p, "finance-mode"); return false; }
        if (!plugin.config().enabled()) { plugin.messages().send(p, "disabled"); return false; }
        Holder h = new Holder(p.getUniqueId()); h.moon = true;
        h.inventory = Bukkit.createInventory(h, 27, plugin.messages().get("moon-title"));
        h.inventory.setItem(4, plugin.items().icon(Material.CLOCK, plugin.messages().get("moon-title"), List.of(
                plugin.messages().get("moon-status-" + plugin.fullMoon().status(p.getWorld())),
                plugin.messages().get("moon-limits", "count", plugin.fullMoon().count()))));
        h.inventory.setItem(10, plugin.items().icon(Material.SHIELD, plugin.messages().get("menu-moon-safety"), plugin.messages().getList("menu-moon-safety-lore")));
        h.inventory.setItem(12, plugin.items().icon(Material.RED_BED, plugin.messages().get("menu-moon-sleep"), plugin.messages().getList("menu-moon-sleep-lore")));
        h.inventory.setItem(14, plugin.items().icon(Material.ZOMBIE_HEAD, plugin.messages().get("menu-moon-waves"), plugin.messages().getList("menu-moon-waves-lore")));
        h.inventory.setItem(16, plugin.items().icon(Material.SUNFLOWER, plugin.messages().get("menu-moon-dawn"), plugin.messages().getList("moon-rules")));
        h.inventory.setItem(18, plugin.items().icon(Material.ARROW, plugin.messages().get("menu-main-back"), List.of()));
        h.inventory.setItem(22, plugin.items().icon(Material.CLOCK, plugin.messages().get("menu-refresh"), plugin.messages().getList("menu-refresh-lore")));
        h.inventory.setItem(26, plugin.items().icon(Material.BARRIER, plugin.messages().get("menu-close"), List.of()));
        p.openInventory(h.inventory); return true;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId())) return;
        int slot = e.getRawSlot();
        if (h.moon ? slot != 18 && slot != 22 && slot != 26 : slot != 27 && slot != 28 && slot != 31 && slot != 35) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != h) return;
            if (slot == (h.moon ? 26 : 35)) { p.closeInventory(); return; }
            if (!plugin.finance().eligible(p)) return;
            if (h.moon) {
                if (slot == 18) plugin.gui().open(p);
                else if (slot == 22) openMoon(p);
            } else {
                if (slot == 27) plugin.bestiaryGui().open(p);
                else if (slot == 28) plugin.gui().open(p);
                else if (slot == 31) openMoon(p);
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    void closeAll() { for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory(); }
}
