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
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Лавка охотника (1.4.155).
 *
 * <p>Ассортимент — бытовые вещи из конфига: еда, свет, расходники. Оружия,
 * брони, инструментов, зачарований и алмазов в лавке нет: монеты охоты не
 * должны превращаться в боевую силу. Клик покупает одну позицию, Shift-клик —
 * до восьми, но не больше суточного лимита.</p>
 */
public final class HuntShopGui implements Listener {

    private static final int[] OFFER_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int INFO_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int SIZE = 54;
    /** Shift-клик покупает не больше этого числа позиций. */
    private static final int BULK = 8;

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final List<String> offerIds;
        private Inventory inventory;

        Holder(UUID owner, List<String> offerIds) {
            this.owner = owner;
            this.offerIds = List.copyOf(offerIds);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HuntPlugin plugin;

    public HuntShopGui(HuntPlugin plugin) {
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
        List<HuntShopOffer> offers = plugin.config().shop();
        List<String> ids = new ArrayList<>();
        for (HuntShopOffer offer : offers) {
            ids.add(offer.id());
        }
        Holder holder = new Holder(player.getUniqueId(), ids);
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("shop-title",
                        "coins", String.valueOf(plugin.service().coins(player))));
        for (int index = 0; index < Math.min(OFFER_SLOTS.length, offers.size()); index++) {
            holder.inventory.setItem(OFFER_SLOTS[index], offerIcon(player, offers.get(index)));
        }
        if (offers.isEmpty()) {
            holder.inventory.setItem(22, plugin.items().icon(Material.BARRIER,
                    plugin.messages().get("shop-empty-name"),
                    List.of(plugin.messages().get("shop-empty-lore"))));
        }
        holder.inventory.setItem(INFO_SLOT, plugin.items().icon(Material.GOLD_NUGGET,
                plugin.messages().get("shop-info-name"),
                List.of(plugin.messages().get("shop-info-lore",
                        "coins", String.valueOf(plugin.service().coins(player)),
                        "earned", String.valueOf(plugin.service().earned(player))))));
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

    private ItemStack offerIcon(Player player, HuntShopOffer offer) {
        ItemStack item = new ItemStack(offer.material(), Math.min(64, offer.amount()));
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().renderTemplate(
                HuntService.itemName(offer.material()).text()));
        long coins = plugin.service().coins(player);
        long earned = plugin.service().earned(player);
        int bought = plugin.store().purchasedToday(player.getUniqueId(), offer.id(),
                System.currentTimeMillis());
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("shop-icon-price",
                "price", String.valueOf(offer.price()),
                "amount", String.valueOf(offer.amount())));
        if (offer.dailyLimit() > 0) {
            lore.add(plugin.messages().get("shop-icon-limit",
                    "limit", String.valueOf(offer.dailyLimit()),
                    "bought", String.valueOf(bought)));
        } else {
            lore.add(plugin.messages().get("shop-icon-limit-none"));
        }
        if (offer.minCoinsEarned() > 0) {
            lore.add(plugin.messages().get("shop-icon-earned",
                    "need", String.valueOf(offer.minCoinsEarned()),
                    "earned", String.valueOf(earned)));
        }
        lore.add(plugin.messages().get("shop-icon-wallet", "coins", String.valueOf(coins)));
        boolean locked = earned < offer.minCoinsEarned();
        lore.add(plugin.messages().get(locked ? "shop-icon-locked" : "shop-icon-click"));
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
        int index = offerIndex(slot);
        if (index < 0 || index >= holder.offerIds.size()) {
            return;
        }
        HuntShopOffer offer = plugin.config().offer(holder.offerIds.get(index));
        if (offer == null) {
            plugin.messages().send(player, "shop-unknown", "id", holder.offerIds.get(index));
            reopen(player);
            return;
        }
        int quantity = 1;
        if (event.isShiftClick()) {
            quantity = BULK;
            if (offer.dailyLimit() > 0) {
                int bought = plugin.store().purchasedToday(player.getUniqueId(), offer.id(),
                        System.currentTimeMillis());
                quantity = Math.max(1, Math.min(BULK, offer.dailyLimit() - bought));
            }
        }
        plugin.service().buy(player, offer.id(), quantity);
        reopen(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static int offerIndex(int slot) {
        for (int index = 0; index < OFFER_SLOTS.length; index++) {
            if (OFFER_SLOTS[index] == slot) {
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
