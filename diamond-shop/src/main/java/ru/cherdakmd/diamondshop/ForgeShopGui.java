package ru.cherdakmd.diamondshop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RC16: раздел «Кузня» ({@code /shop forge}) — восемь настоящих PDC-расходников.
 * Только покупка, обратной скупки нет. Иконки не являются товаром: выдача идёт
 * через frozen grant в {@link ShopExchange}, а не через {@code addItem}.
 */
final class ForgeShopGui implements Listener {

    static final class Holder implements InventoryHolder {
        final UUID owner;
        final List<ForgeShopService.Offer> offers;
        private Inventory inventory;
        Holder(UUID owner, List<ForgeShopService.Offer> offers) {
            this.owner = owner;
            this.offers = List.copyOf(offers);
        }
        void bind(Inventory inventory) { this.inventory = inventory; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private final DiamondShopPlugin plugin;

    ForgeShopGui(DiamondShopPlugin plugin) { this.plugin = plugin; }

    void open(Player player) {
        if (!plugin.shopService().ready(player)) { plugin.messages().send(player, "trading.unavailable"); return; }
        var offers = plugin.forgeShop().offers(player);
        var holder = new Holder(player.getUniqueId(), offers);
        Inventory inventory = Bukkit.createInventory(holder, ShopLayout.SIZE,
                plugin.messages().get("forge.shop.title"));
        holder.bind(inventory);
        inventory.setItem(ShopLayout.SLOT_CLOSE,
                named(Material.BARRIER, "trading.gui.close-name"));
        inventory.setItem(ShopLayout.SLOT_BALANCE, balanceIcon(player));
        inventory.setItem(ShopLayout.SLOT_FORGE,
                named(Material.ANVIL, "forge.shop.back-name"));
        if (offers.isEmpty()) {
            inventory.setItem(ShopLayout.FIRST_ITEM_SLOT + 13,
                    named(Material.BARRIER, "forge.shop.empty"));
        } else {
            for (int index = 0; index < offers.size() && index < 8; index++) {
                inventory.setItem(ShopLayout.FIRST_ITEM_SLOT + 9 + index, iconOf(offers.get(index)));
            }
        }
        player.openInventory(inventory);
    }

    private ItemStack iconOf(ForgeShopService.Offer offer) {
        ItemStack icon = offer.prototype().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            Messages messages = plugin.messages();
            // Сначала — ЧТО делает предмет и КАК его применить, затем цена и клики.
            List<Component> lore = new ArrayList<>(ForgeShopText.purpose(messages, offer.sku()));
            lore.add(messages.get("forge.shop.details-hint"));
            lore.addAll(messages.getList("forge.shop.item-lore",
                    "sku", offer.sku(), "name", ForgeShopText.name(messages, offer.sku()),
                    "price", offer.price()));
            if (offer.seed()) lore.add(messages.get("forge.shop.max-one"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack balanceIcon(Player player) {
        ItemStack icon = new ItemStack(Material.DIAMOND);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get("trading.gui.balance-name",
                    "balance", plugin.shopService().balanceText(player)));
            meta.lore(List.of(plugin.messages().get("trading.gui.balance-lore",
                    "turnover", PriceEngine.round(plugin.shopService().store().turnover()),
                    "physical", plugin.shopService().physical(player))));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack named(Material material, String nameKey) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) { meta.displayName(plugin.messages().get(nameKey)); icon.setItemMeta(meta); }
        return icon;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.getClick().isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK
                    || event.getClick() == ClickType.NUMBER_KEY) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        if (slot == ShopLayout.SLOT_CLOSE) { player.closeInventory(); return; }
        if (slot == ShopLayout.SLOT_FORGE) { plugin.gui().open(player, 0); return; }
        if (slot == ShopLayout.SLOT_BALANCE) return;
        int index = slot - (ShopLayout.FIRST_ITEM_SLOT + 9);
        if (index < 0 || index >= Math.min(8, holder.offers.size())) return;
        var offer = holder.offers.get(index);
        if (event.getClick() == ClickType.RIGHT) {
            plugin.messages().send(player, "forge.shop.no-buyback");
            return;
        }
        // Повторно проверяем актуальность цены/производителя в момент выбора.
        var current = plugin.forgeShop().offers(player);
        if (current.stream().noneMatch(o -> o.sku().equals(offer.sku()) && o.price() == offer.price())) {
            plugin.messages().send(player, "forge.shop.closed");
            return;
        }
        plugin.quantities().openForge(player, offer, ShopCategory.ALL, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
