package ru.cherdakmd.chrdkhunt;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Коллекция трофеев (1.4.155).
 *
 * <p>Трофей стоит осколки своего семейства и монеты, но даёт только коллекцию
 * и предмет для рамки — никакой силы. Уже полученный трофей можно забрать
 * повторно бесплатно: косметика не должна теряться вместе с вещами.</p>
 */
public final class HuntTrophyGui implements Listener {

    private static final int[] TROPHY_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int INFO_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int SIZE = 54;

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final List<String> trophyIds;
        private Inventory inventory;

        Holder(UUID owner, List<String> trophyIds) {
            this.owner = owner;
            this.trophyIds = List.copyOf(trophyIds);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HuntPlugin plugin;

    public HuntTrophyGui(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        List<HuntTrophy> trophies = plugin.config().trophies();
        List<String> ids = new ArrayList<>();
        for (HuntTrophy trophy : trophies) {
            ids.add(trophy.id());
        }
        Holder holder = new Holder(player.getUniqueId(), ids);
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("trophy-title",
                        "owned", String.valueOf(plugin.service().trophyCount(player)),
                        "total", String.valueOf(trophies.size())));
        for (int index = 0; index < Math.min(TROPHY_SLOTS.length, trophies.size()); index++) {
            holder.inventory.setItem(TROPHY_SLOTS[index], trophyIcon(player, trophies.get(index)));
        }
        if (trophies.isEmpty()) {
            holder.inventory.setItem(22, plugin.items().icon(Material.BARRIER,
                    plugin.messages().get("trophy-empty-name"),
                    List.of(plugin.messages().get("trophy-empty-lore"))));
        }
        holder.inventory.setItem(INFO_SLOT, plugin.items().icon(Material.GOLD_NUGGET,
                plugin.messages().get("trophy-info-name"),
                List.of(plugin.messages().get("trophy-info-lore",
                        "owned", String.valueOf(plugin.service().trophyCount(player)),
                        "total", String.valueOf(trophies.size()),
                        "coins", String.valueOf(plugin.service().coins(player))))));
        holder.inventory.setItem(CLOSE_SLOT, plugin.items().icon(Material.BARRIER,
                plugin.messages().get("menu-close"), List.of()));
        player.openInventory(holder.inventory);
    }

    public void reopen(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> open(player));
    }

    private ItemStack trophyIcon(Player player, HuntTrophy trophy) {
        ItemStack item = new ItemStack(trophy.material());
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().renderTemplate(trophy.displayName()));
        HuntFamily family = plugin.config().family(trophy.familyId());
        String shardName = family == null ? "осколки" : family.shardName();
        long have = plugin.service().shards(player, trophy.familyId());
        long earned = plugin.service().earned(player);
        boolean owned = plugin.service().hasTrophy(player, trophy.id());
        boolean locked = earned < trophy.minCoinsEarned();
        List<Component> lore = new ArrayList<>();
        for (String line : trophy.lore()) {
            lore.add(plugin.messages().renderTemplate(line));
        }
        if (trophy.shards() > 0 || trophy.coins() > 0) {
            lore.add(plugin.messages().get("trophy-icon-cost",
                    "shards", String.valueOf(trophy.shards()),
                    "shard", shardName,
                    "coins", String.valueOf(trophy.coins())));
        }
        if (trophy.shards() > 0) {
            lore.add(plugin.messages().get("trophy-icon-shards",
                    "have", String.valueOf(have),
                    "need", String.valueOf(trophy.shards())));
        }
        if (trophy.minCoinsEarned() > 0) {
            lore.add(plugin.messages().get("trophy-icon-earned",
                    "need", String.valueOf(trophy.minCoinsEarned()),
                    "earned", String.valueOf(earned)));
        }
        if (owned) {
            lore.add(plugin.messages().get("trophy-icon-owned"));
        } else if (locked) {
            lore.add(plugin.messages().get("trophy-icon-locked"));
        } else {
            lore.add(plugin.messages().get("trophy-icon-click"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        int index = trophyIndex(slot);
        if (index < 0 || index >= holder.trophyIds.size()) {
            return;
        }
        String id = holder.trophyIds.get(index);
        if (plugin.service().hasTrophy(player, id)) {
            plugin.service().claimTrophy(player, id);
        } else {
            plugin.service().craftTrophy(player, id);
        }
        reopen(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static int trophyIndex(int slot) {
        for (int index = 0; index < TROPHY_SLOTS.length; index++) {
            if (TROPHY_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }
}
