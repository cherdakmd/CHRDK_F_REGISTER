package ru.cherdakmd.diamondshop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Редактор каталога «вне конфига» (7 ноября 2026).
 *
 * <p>{@code /shopadmin edit} открывает список товаров; клик по товару открывает
 * карточку с кнопками изменения базовой цены, запаса, тарифа, раздела и скупки,
 * удаления и сброса правок. Все изменения живут в черновике
 * {@link ShopOverrides} до кнопки «Сохранить»: config.yml не перезаписывается
 * ни на одном шаге, а запись идёт в {@code shop-overrides.json} в папке плагина.</p>
 *
 * <p>Право — {@code chrdkshop.admin}; проверяется и при открытии, и на каждом
 * клике. Закрытие окна без сохранения отбрасывает черновик.</p>
 */
public final class ShopAdminGui implements Listener {

    private static final int SIZE = 54;
    private static final int EDITOR_SIZE = 27;
    private static final int PER_PAGE = 27;
    private static final int FIRST_ITEM_SLOT = 18;

    private static final int LIST_CLOSE = 17;
    private static final int LIST_PREV = 45;
    private static final int LIST_SAVE = 49;
    private static final int LIST_NEXT = 53;
    /** Кнопка «редактор» в витрине игрока (свободный слот второго ряда). */
    static final int SHOP_EDIT_SLOT = 16;

    private static final int ED_ICON = 10;
    private static final int ED_PRICE_DOWN = 12;
    private static final int ED_PRICE_UP = 14;
    private static final int ED_PRICE_DOWN_SMALL = 11;
    private static final int ED_PRICE_UP_SMALL = 15;
    private static final int ED_STOCK_DOWN = 19;
    private static final int ED_STOCK_UP = 20;
    private static final int ED_SELL = 21;
    private static final int ED_CATEGORY = 22;
    private static final int ED_TARIFF_DOWN = 23;
    private static final int ED_TARIFF_UP = 24;
    private static final int ED_BACK = 18;
    private static final int ED_REMOVE = 16;
    private static final int ED_RESTORE = 17;
    private static final int ED_SAVE = 26;

    private final DiamondShopPlugin plugin;

    public ShopAdminGui(DiamondShopPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private boolean allowed(Player player) {
        return player != null && player.hasPermission("chrdkshop.admin");
    }

    /* ------------------------------- список товаров ------------------------------- */

    /** Holder списка: страница, слот → материал и общий черновик правок. */
    private static final class ListHolder implements InventoryHolder {
        private final int page;
        private final Map<Integer, String> slots = new HashMap<>();
        private final ShopOverrides draft;
        private Inventory inventory;

        ListHolder(int page, ShopOverrides draft) {
            this.page = page;
            this.draft = draft;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** Holder карточки товара. */
    private static final class EditorHolder implements InventoryHolder {
        private final String material;
        private final ShopOverrides draft;
        private final int listPage;
        private boolean confirmRemove;
        private Inventory inventory;

        EditorHolder(String material, ShopOverrides draft, int listPage) {
            this.material = material;
            this.draft = draft;
            this.listPage = listPage;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** Открыть список со свежим черновиком (обычный вход по команде). */
    public void open(Player player, int page) {
        open(player, page, plugin.overrides().copy());
    }

    private void open(Player player, int page, ShopOverrides draft) {
        if (!allowed(player)) {
            msg().send(player, "tools.permission");
            return;
        }
        List<ShopConfig.ShopItem> all = catalog(draft);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int safePage = Math.clamp(page, 0, pages - 1);
        ListHolder holder = new ListHolder(safePage, draft);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                msg().get("admin.gui.title", "page", safePage + 1, "pages", pages));
        holder.bind(inventory);

        int from = safePage * PER_PAGE;
        for (int slot = 0; slot < PER_PAGE && from + slot < all.size(); slot++) {
            ShopConfig.ShopItem item = all.get(from + slot);
            holder.slots.put(FIRST_ITEM_SLOT + slot, item.material());
            inventory.setItem(FIRST_ITEM_SLOT + slot, listIcon(item, draft));
        }
        if (all.isEmpty()) {
            inventory.setItem(FIRST_ITEM_SLOT + 13, named(Material.BARRIER, "admin.gui.empty"));
        }
        inventory.setItem(LIST_CLOSE, named(Material.BARRIER, "admin.gui.close-name"));
        inventory.setItem(LIST_SAVE, named(Material.DIAMOND, "admin.gui.save-name"));
        if (safePage > 0) {
            inventory.setItem(LIST_PREV, named(Material.ARROW, "admin.gui.prev-name"));
        }
        if (safePage < pages - 1) {
            inventory.setItem(LIST_NEXT, named(Material.ARROW, "admin.gui.next-name"));
        }
        player.openInventory(inventory);
    }

    private List<ShopConfig.ShopItem> catalog(ShopOverrides draft) {
        var config = plugin.shopService().config();
        return draft.apply(config.items(), new java.util.HashSet<>(config.retiredMaterials()));
    }

    /** Эталонный товар из уже загруженного каталога (с сохранёнными правками). */
    private ShopConfig.ShopItem baseItem(String material) {
        return plugin.shopService().config().items().stream()
                .filter(item -> item.material().equalsIgnoreCase(material))
                .findFirst().orElse(null);
    }

    /** Товар с наложенным черновиком — то, что увидит игрок после сохранения. */
    private ShopConfig.ShopItem effective(ShopOverrides draft, String material) {
        ShopConfig.ShopItem base = baseItem(material);
        if (base == null) {
            return null;
        }
        ShopOverrides.Change change = draft.change(material);
        return change.equals(ShopOverrides.Change.EMPTY) ? base : change.applyTo(base);
    }

    private ItemStack listIcon(ShopConfig.ShopItem item, ShopOverrides draft) {
        Material material = item.isBook() ? Material.ENCHANTED_BOOK
                : Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) {
            material = Material.PAPER;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(item.material().toLowerCase(Locale.ROOT)));
            List<Component> lore = new ArrayList<>(msg().getList("admin.gui.item-lore",
                    "price", PriceEngine.round(item.basePrice()),
                    "stock", item.refStock(),
                    "category", item.category(),
                    "tariff", item.purchaseMultiplier(),
                    "sell", item.sellEnabled() ? "да" : "нет",
                    "edited", draft.change(item.material()).equals(ShopOverrides.Change.EMPTY)
                            ? "нет" : "да"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /* ------------------------------- карточка товара ------------------------------- */

    private void openEditor(Player player, String material, ShopOverrides draft, int listPage) {
        if (!allowed(player)) {
            msg().send(player, "tools.permission");
            return;
        }
        ShopConfig.ShopItem item = effective(draft, material);
        if (item == null) {
            msg().send(player, "trading.unknown-item", "material", material.toLowerCase(Locale.ROOT));
            open(player, listPage, draft);
            return;
        }
        EditorHolder holder = new EditorHolder(material, draft, listPage);
        Inventory inventory = Bukkit.createInventory(holder, EDITOR_SIZE,
                msg().get("admin.gui.editor-title", "material", material.toLowerCase(Locale.ROOT)));
        holder.bind(inventory);
        render(inventory, holder, item);
        player.openInventory(inventory);
    }

    private void render(Inventory inventory, EditorHolder holder, ShopConfig.ShopItem item) {
        inventory.clear();
        Material material = item.isBook() ? Material.ENCHANTED_BOOK
                : Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) {
            material = Material.PAPER;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(item.material().toLowerCase(Locale.ROOT)));
            meta.lore(msg().getList("admin.gui.item-lore",
                    "price", PriceEngine.round(item.basePrice()),
                    "stock", item.refStock(),
                    "category", item.category(),
                    "tariff", item.purchaseMultiplier(),
                    "sell", item.sellEnabled() ? "да" : "нет",
                    "edited", holder.draft.change(item.material()).equals(ShopOverrides.Change.EMPTY)
                            ? "нет" : "да"));
            icon.setItemMeta(meta);
        }
        inventory.setItem(ED_ICON, icon);

        inventory.setItem(ED_PRICE_DOWN_SMALL, button(Material.RED_CONCRETE,
                "admin.gui.price-down-small", "price",
                PriceEngine.round(Math.max(0.01d, item.basePrice() * 0.75d))));
        inventory.setItem(ED_PRICE_DOWN, button(Material.RED_WOOL,
                "admin.gui.price-down", "price", PriceEngine.round(Math.max(0.01d, item.basePrice() - 1))));
        inventory.setItem(ED_PRICE_UP, button(Material.LIME_WOOL,
                "admin.gui.price-up", "price", PriceEngine.round(item.basePrice() + 1)));
        inventory.setItem(ED_PRICE_UP_SMALL, button(Material.LIME_CONCRETE,
                "admin.gui.price-up-small", "price", PriceEngine.round(item.basePrice() * 1.25d)));

        inventory.setItem(ED_STOCK_DOWN, button(Material.BLUE_WOOL,
                "admin.gui.stock-down", "stock", Math.max(1, item.refStock() - 100)));
        inventory.setItem(ED_STOCK_UP, button(Material.LIGHT_BLUE_WOOL,
                "admin.gui.stock-up", "stock", item.refStock() + 100));
        inventory.setItem(ED_SELL, button(item.sellEnabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                "admin.gui.field-sell", "value", item.sellEnabled() ? "да" : "нет"));
        inventory.setItem(ED_CATEGORY, button(Material.BOOKSHELF,
                "admin.gui.field-category", "value", item.category()));
        inventory.setItem(ED_TARIFF_DOWN, button(Material.ORANGE_WOOL,
                "admin.gui.tariff-down", "value", Math.max(1, (int) item.purchaseMultiplier() - 1)));
        inventory.setItem(ED_TARIFF_UP, button(Material.YELLOW_WOOL,
                "admin.gui.tariff-up", "value", Math.min(100, (int) item.purchaseMultiplier() + 1)));

        inventory.setItem(ED_BACK, named(Material.ARROW, "admin.gui.back-name"));
        inventory.setItem(ED_RESTORE, named(Material.WATER_BUCKET, "admin.gui.restore-name"));
        inventory.setItem(ED_REMOVE, button(holder.confirmRemove ? Material.TNT : Material.BARRIER,
                holder.confirmRemove ? "admin.gui.remove-confirm" : "admin.gui.remove-name",
                "material", item.material().toLowerCase(Locale.ROOT)));
        inventory.setItem(ED_SAVE, named(Material.DIAMOND, "admin.gui.save-name"));
    }

    private ItemStack button(Material material, String key, Object... values) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(msg().get(key, values));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack named(Material material, String key) {
        return button(material, key);
    }

    /* ------------------------------- обработчики ------------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof ListHolder) && !(holder instanceof EditorHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !allowed(player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (holder instanceof ListHolder list) {
            handleList(player, list, slot);
        } else {
            handleEditor(player, (EditorHolder) holder, slot);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    private void handleList(Player player, ListHolder holder, int slot) {
        if (slot == LIST_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == LIST_SAVE) {
            commit(player, holder.draft, holder.page);
            return;
        }
        if (slot == LIST_PREV || slot == LIST_NEXT) {
            open(player, holder.page + (slot == LIST_NEXT ? 1 : -1), holder.draft);
            return;
        }
        String material = holder.slots.get(slot);
        if (material != null) {
            openEditor(player, material, holder.draft, holder.page);
        }
    }

    private void handleEditor(Player player, EditorHolder holder, int slot) {
        String material = holder.material;
        ShopConfig.ShopItem item = effective(holder.draft, material);
        if (item == null) {
            open(player, holder.listPage, holder.draft);
            return;
        }
        String value;
        switch (slot) {
            case ED_PRICE_DOWN_SMALL -> value = String.valueOf(
                    PriceEngine.round(Math.max(0.01d, item.basePrice() * 0.75d)));
            case ED_PRICE_DOWN -> value = String.valueOf(PriceEngine.round(Math.max(0.01d, item.basePrice() - 1)));
            case ED_PRICE_UP -> value = String.valueOf(PriceEngine.round(item.basePrice() + 1));
            case ED_PRICE_UP_SMALL -> value = String.valueOf(PriceEngine.round(item.basePrice() * 1.25d));
            case ED_STOCK_DOWN -> value = String.valueOf(Math.max(1, item.refStock() - 100));
            case ED_STOCK_UP -> value = String.valueOf(item.refStock() + 100);
            case ED_SELL -> value = item.sellEnabled() ? "false" : "true";
            case ED_CATEGORY -> value = nextCategory(item.category());
            case ED_TARIFF_DOWN -> value = String.valueOf(Math.max(1, (int) item.purchaseMultiplier() - 1));
            case ED_TARIFF_UP -> value = String.valueOf(Math.min(100, (int) item.purchaseMultiplier() + 1));
            default -> value = null;
        }
        if (value != null) {
            String field = switch (slot) {
                case ED_PRICE_DOWN_SMALL, ED_PRICE_DOWN, ED_PRICE_UP, ED_PRICE_UP_SMALL -> "base-price";
                case ED_STOCK_DOWN, ED_STOCK_UP -> "ref-stock";
                case ED_SELL -> "sell-enabled";
                case ED_CATEGORY -> "category";
                default -> "purchase-multiplier";
            };
            if (!holder.draft.set(material, field, value)) {
                msg().send(player, "admin.gui.invalid");
            }
            holder.confirmRemove = false;
            refresh(player, holder);
            return;
        }
        switch (slot) {
            case ED_BACK -> open(player, holder.listPage, holder.draft);
            case ED_SAVE -> commit(player, holder.draft, holder.listPage);
            case ED_RESTORE -> {
                holder.draft.restore(material);
                msg().send(player, "admin.gui.restored", "material", material.toLowerCase(Locale.ROOT));
                open(player, holder.listPage, holder.draft);
            }
            case ED_REMOVE -> {
                if (!holder.confirmRemove) {
                    holder.confirmRemove = true;
                    refresh(player, holder);
                } else {
                    holder.draft.remove(material);
                    msg().send(player, "admin.gui.removed", "material", material.toLowerCase(Locale.ROOT));
                    open(player, holder.listPage, holder.draft);
                }
            }
            default -> { }
        }
    }

    private void refresh(Player player, EditorHolder holder) {
        ShopConfig.ShopItem item = effective(holder.draft, holder.material);
        if (item == null) {
            open(player, holder.listPage, holder.draft);
            return;
        }
        render(holder.getInventory(), holder, item);
        player.updateInventory();
    }

    private void commit(Player player, ShopOverrides draft, int page) {
        plugin.applyOverrides(draft);
        msg().send(player, "admin.gui.saved");
        // applyOverrides перечитывает конфиг и закрывает наши окна — открываем список заново.
        open(player, page);
    }

    private String nextCategory(String current) {
        List<String> ids = ShopOverrides.categories();
        int index = ids.indexOf(current);
        return ids.isEmpty() ? current : ids.get((index + 1 + ids.size()) % ids.size());
    }

    /** Закрыть все редакторы при reload/onDisable, чтобы не оставить черновик в воздухе. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ListHolder || holder instanceof EditorHolder) {
                player.closeInventory();
            }
        }
    }
}
