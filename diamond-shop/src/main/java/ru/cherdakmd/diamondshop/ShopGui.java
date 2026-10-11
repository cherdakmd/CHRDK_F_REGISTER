package ru.cherdakmd.diamondshop;

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

import java.util.List;

/**
 * Инвентарная витрина магазина (54 слота: 45 товаров + нижний ряд управления).
 * ЛКМ/Shift+ЛКМ — покупка 1/64, ПКМ/Shift+ПКМ — продажа 1/64. Содержимое перечитывается
 * при каждом действии, поэтому динамические цены всегда актуальны.
 */
public final class ShopGui implements Listener {

    private static final int SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BALANCE = 49;
    private static final int SLOT_NEXT = 53;

    private final DiamondShopPlugin plugin;

    public ShopGui(DiamondShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, int page) {
        List<ShopConfig.ShopItem> items = plugin.shopService().config().items();
        int maxPage = Math.max(0, (items.size() - 1) / ITEMS_PER_PAGE);
        page = Math.clamp(page, 0, maxPage);

        ShopHolder holder = new ShopHolder(page, items);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, plugin.messages().get("shop.gui.title"));
        holder.bind(inventory);

        int from = page * ITEMS_PER_PAGE;
        for (int slot = 0; slot < ITEMS_PER_PAGE && from + slot < items.size(); slot++) {
            inventory.setItem(slot, iconOf(items.get(from + slot), player));
        }
        if (page > 0) {
            inventory.setItem(SLOT_PREV, named(Material.ARROW, "shop.gui.prev-name"));
        }
        inventory.setItem(SLOT_BALANCE, balanceIcon(player));
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, named(Material.ARROW, "shop.gui.next-name"));
        }
        player.openInventory(inventory);
    }

    private ItemStack iconOf(ShopConfig.ShopItem item, Player player) {
        Material material = Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) material = Material.BARRIER;
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(
                    item.material(), net.kyori.adventure.text.format.NamedTextColor.WHITE));
            ShopService service = plugin.shopService();
            long now = System.currentTimeMillis();
            long stock = service.store().stockOf(item, now, service.config().stockRecoveryPerHour());
            double price = service.priceOf(item);
            meta.lore(plugin.messages().getList("shop.gui.item-lore",
                    "price", PriceEngine.round(price),
                    "stock", stock,
                    "ref", item.refStock()));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack balanceIcon(Player player) {
        ItemStack icon = new ItemStack(Material.DIAMOND);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            ShopService service = plugin.shopService();
            meta.displayName(plugin.messages().get("shop.gui.balance-name",
                    "balance", PriceEngine.round(service.store().balanceOf(player.getUniqueId()))));
            meta.lore(List.of(plugin.messages().get("shop.gui.balance-lore",
                    "turnover", PriceEngine.round(service.store().turnover()))));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack named(Material material, String nameKey) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /* ------------------------------- обработчики ------------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }
        // Нижняя половина (инвентарь игрока) работает как обычно, КРОМЕ действий,
        // способных двинуть предметы в витрину или снять иконку курсором.
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.getClick().isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK
                    || event.getClick() == ClickType.NUMBER_KEY) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot == SLOT_PREV) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, holder.page() - 1));
            return;
        }
        if (slot == SLOT_NEXT) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, holder.page() + 1));
            return;
        }
        if (slot == SLOT_BALANCE || slot >= ITEMS_PER_PAGE || slot < 0) {
            return;
        }
        int index = holder.page() * ITEMS_PER_PAGE + slot;
        List<ShopConfig.ShopItem> items = plugin.shopService().config().items();
        if (index >= items.size()) return;
        ShopConfig.ShopItem item = items.get(index);

        ClickType click = event.getClick();
        long units = click.isShiftClick() ? 64 : 1;
        if (click == ClickType.NUMBER_KEY || click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            return;
        }
        if (click.isLeftClick()) {
            trade(player, item, units, true);
        } else if (click.isRightClick()) {
            trade(player, item, units, false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    private void trade(Player player, ShopConfig.ShopItem item, long units, boolean buy) {
        ShopService.TradeResult result = buy
                ? plugin.shopService().buy(player, item, units)
                : plugin.shopService().sell(player, item, units);
        if (result.success()) {
            String key = buy ? "shop.buy-done" : "shop.sell-done";
            plugin.messages().send(player, key,
                    "material", item.material().toLowerCase(java.util.Locale.ROOT),
                    "units", units,
                    buy ? "cost" : "income", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
        } else if (result.errorKey() != null && result.errorKey().startsWith("shop.")) {
            plugin.messages().send(player, result.errorKey(),
                    "material", item.material().toLowerCase(java.util.Locale.ROOT),
                    "units", units,
                    "cost", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())),
                    "value", units);
        }
        // перечитываем витрину с новыми ценами
        plugin.getServer().getScheduler().runTask(plugin,
                () -> open(player, player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder holder ? holder.page() : 0));
    }

    /** Holder с номером страницы и ссылкой на каталог. */
    private static final class ShopHolder implements InventoryHolder {

        private final int page;
        private final List<ShopConfig.ShopItem> items;
        private Inventory inventory;

        private ShopHolder(int page, List<ShopConfig.ShopItem> items) {
            this.page = page;
            this.items = items;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
