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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Инвентарная витрина магазина с вкладками разделов (1.4.125).
 *
 * <p>Раскладка — {@link ShopLayout}: верхний ряд занимают вкладки разделов и
 * кнопка закрытия, в середине 36 товаров на страницу, снизу пагинация и баланс.
 * ЛКМ/Shift+ЛКМ — покупка 1/64, ПКМ/Shift+ПКМ — продажа 1/64. Содержимое
 * перечитывается при каждом действии, поэтому динамические цены всегда актуальны.</p>
 */
public final class ShopGui implements Listener {

    private final DiamondShopPlugin plugin;

    public ShopGui(DiamondShopPlugin plugin) {
        this.plugin = plugin;
    }

    /** Открыть витрину на разделе «Все». */
    public void open(Player player, int page) {
        open(player, ShopCategory.ALL, page);
    }

    /** Открыть витрину на конкретном разделе и странице. */
    public void open(Player player, ShopCategory category, int page) {
        if (!plugin.shopService().ready(player)) { plugin.messages().send(player, "shop.unavailable"); return; }
        List<ShopConfig.ShopItem> all = plugin.shopService().config().items();
        List<ShopCategory> tabs = ShopCatalog.tabs(all);
        ShopCategory current = category == null || !tabs.contains(category)
                ? ShopCategory.ALL : category;
        List<ShopConfig.ShopItem> view = ShopCatalog.filter(all, current);
        int pages = ShopLayout.pages(view.size());
        int safePage = ShopLayout.clampPage(page, view.size());

        ShopHolder holder = new ShopHolder(current, safePage, plugin.shopService());
        Component title = plugin.messages().get("shop.gui.title")
                .append(plugin.messages().get("shop.gui.title-suffix",
                        "category", plugin.messages().mini(current.messageKey()),
                        "page", safePage + 1,
                        "pages", pages));
        Inventory inventory = Bukkit.createInventory(holder, ShopLayout.SIZE, title);
        holder.bind(inventory);

        for (int index = 0; index < tabs.size() && index < ShopLayout.MAX_TABS; index++) {
            ShopCategory tab = tabs.get(index);
            int count = ShopCatalog.filter(all, tab).size();
            inventory.setItem(index, tabIcon(tab, tab == current, count));
        }
        inventory.setItem(ShopLayout.SLOT_CLOSE, named(Material.BARRIER, "shop.gui.close-name"));

        int from = safePage * ShopLayout.ITEMS_PER_PAGE;
        for (int slot = 0; slot < ShopLayout.ITEMS_PER_PAGE && from + slot < view.size(); slot++) {
            var item = view.get(from + slot); var service = plugin.shopService();
            var offer = new Offer(item.material(), service.batchSize(item), service.askPrice(item), service.bidPrice(item));
            holder.offers.put(ShopLayout.FIRST_ITEM_SLOT + slot, offer);
            inventory.setItem(ShopLayout.FIRST_ITEM_SLOT + slot, iconOf(item, offer));
        }
        if (view.isEmpty()) {
            inventory.setItem(ShopLayout.FIRST_ITEM_SLOT + 13,
                    named(Material.BARRIER, "shop.gui.empty"));
        }
        if (safePage > 0) {
            inventory.setItem(ShopLayout.SLOT_PREV, named(Material.ARROW, "shop.gui.prev-name"));
        }
        inventory.setItem(ShopLayout.SLOT_BALANCE, balanceIcon(player));
        if (safePage < pages - 1) {
            inventory.setItem(ShopLayout.SLOT_NEXT, named(Material.ARROW, "shop.gui.next-name"));
        }
        player.openInventory(inventory);
    }

    private ItemStack tabIcon(ShopCategory category, boolean selected, int count) {
        Material material = Material.matchMaterial(category.icon());
        ItemStack icon = new ItemStack(material == null || !material.isItem()
                ? Material.PAPER : material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(category.messageKey()));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("shop.gui.tab-lore", "count", count));
            if (selected) {
                lore.add(plugin.messages().get("shop.gui.tab-current"));
                meta.setEnchantmentGlintOverride(true);
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /**
     * Иконка товара. Имя не переопределяется — клиент показывает локализованное
     * название материала; в лоре остаются цены, тренд и id для {@code /shop buy}.
     */
    private ItemStack iconOf(ShopConfig.ShopItem item, Offer offer) {
        Material material = Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) {
            material = Material.BARRIER;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            ShopService service = plugin.shopService();
            long now = System.currentTimeMillis();
            long stock = service.store().stockOf(item, now, service.config().stockRecoveryPerHour());
            double price = service.priceOf(item);
            double[] history = service.historyOf(item);
            List<Component> lore = new ArrayList<>(plugin.messages().getList("shop.gui.item-lore",
                    "price", PriceEngine.round(price),
                    "bid", offer.bid(),
                    "ask", offer.ask(), "batch", offer.batch(),
                    "trend", PriceEngine.trend(history),
                    "history", PriceEngine.sparkline(history),
                    "stock", stock,
                    "ref", item.refStock()));
            lore.add(plugin.messages().get("shop.gui.item-id",
                    "id", item.material().toLowerCase(Locale.ROOT)));
            meta.lore(lore);
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
                    "balance", service.balance(player)));
            meta.lore(List.of(plugin.messages().get("shop.gui.balance-lore",
                    "turnover", PriceEngine.round(service.store().turnover()), "physical", service.physical(player))));
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
        if (slot == ShopLayout.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (ShopLayout.isTabSlot(slot)) {
            List<ShopCategory> tabs = ShopCatalog.tabs(plugin.shopService().config().items());
            if (slot < tabs.size()) {
                ShopCategory target = tabs.get(slot);
                reopen(player, target, 0);
            }
            return;
        }
        if (slot == ShopLayout.SLOT_PREV) {
            reopen(player, holder.category(), holder.page() - 1);
            return;
        }
        if (slot == ShopLayout.SLOT_NEXT) {
            reopen(player, holder.category(), holder.page() + 1);
            return;
        }
        if (slot == ShopLayout.SLOT_BALANCE) {
            return;
        }
        int index = ShopLayout.itemIndex(holder.page(), slot);
        if (index < 0) {
            return;
        }
        List<ShopConfig.ShopItem> view = ShopCatalog.filter(
                plugin.shopService().config().items(), holder.category());
        if (index >= view.size()) {
            return;
        }
        ShopConfig.ShopItem item = view.get(index);
        var offer = holder.offers.get(slot); var service = plugin.shopService();
        if (holder.service != service || offer == null || !offer.id().equals(item.material())
                || offer.batch() != service.batchSize(item) || offer.ask() != service.askPrice(item) || offer.bid() != service.bidPrice(item)) {
            plugin.messages().send(player, "shop.price-changed"); reopen(player, holder.category(), holder.page()); return;
        }

        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY || click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            return;
        }
        long units = click.isShiftClick() ? 16 : 1;
        if (click.isLeftClick()) {
            trade(player, item, units, true, holder.category(), holder.page(), offer);
        } else if (click.isRightClick()) {
            trade(player, item, units, false, holder.category(), holder.page(), offer);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    private void trade(Player player, ShopConfig.ShopItem item, long units, boolean buy,
                       ShopCategory category, int page, Offer offer) {
        ShopService.TradeResult result = plugin.shopService().quoted(player, item, units, buy, offer.batch(), buy ? offer.ask() : offer.bid());
        if (result.success()) {
            if (buy) plugin.messages().send(player, "shop.payment-detail", "account", result.bankPaid(), "physical", result.physicalPaid(), "change", result.change());
            String key = buy ? "shop.buy-done" : "shop.sell-done";
            plugin.messages().send(player, key,
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "units", result.units(),
                    buy ? "cost" : "income", result.money(),
                    "balance", plugin.shopService().balance(player));
        } else if (result.errorKey() != null && result.errorKey().startsWith("shop.")) {
            plugin.messages().send(player, result.errorKey(),
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "units", result.units(),
                    "cost", result.money(),
                    "balance", plugin.shopService().balance(player),
                    "value", units);
        }
        // перечитываем витрину с новыми ценами, оставаясь в том же разделе
        reopen(player, category, page);
    }

    /** Перерисовка витрины следующим тиком — после завершения текущего клика. */
    private void reopen(Player player, ShopCategory category, int page) {
        var expected = player.getOpenInventory().getTopInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled() && player.isOnline() && player.getOpenInventory().getTopInventory() == expected) open(player, category, page);
        });
    }

    /** Holder с разделом и номером страницы. */
    public void closeAll() {
        for (var player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder) player.closeInventory();
    }
    private record Offer(String id, int batch, long ask, long bid) { }
    private static final class ShopHolder implements InventoryHolder {

        private final ShopCategory category;
        private final int page;
        private Inventory inventory;
        private final ShopService service;
        private final java.util.Map<Integer, Offer> offers = new java.util.HashMap<>();

        private ShopHolder(ShopCategory category, int page, ShopService service) {
            this.service = service;
            this.category = category;
            this.page = page;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        private ShopCategory category() {
            return category;
        }

        private int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
