package ru.cherdakmd.chrdkclaims;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Меню приватов (1.4.120): сетка карточек с постраничной навигацией и двумя
 * вкладками — «мои приваты» и «на продажу». Левый клик по карточке телепортирует
 * к привату (с разбегом и кулдауном из {@link ClaimTeleportService}), правый —
 * печатает сводку в чат.
 *
 * <p>Разметка: шапка 0..8 (назад, сводка, вперёд), четыре ряда карточек
 * 10..16 / 19..25 / 28..34 / 37..43, подвал — вкладки 45 и 46 и кнопка закрытия 49.
 * Все прочие слоты закрыты рамкой, клики и перетаскивания отменяются, поэтому
 * предметы меню нельзя вынести в инвентарь.</p>
 */
public final class ClaimMenuGui implements Listener {

    private static final int SIZE = 54;
    private static final int SLOT_PREV = 0;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_NEXT = 8;
    private static final int SLOT_TAB_MINE = 45;
    private static final int SLOT_TAB_SALE = 46;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_CLAN = 48;
    private static final int SLOT_TAB_ACCESS = 47;
    /** Центр верхнего ряда карточек — пустое состояние. */
    private static final int SLOT_EMPTY = 22;

    /** Ряды карточек: 7 колонок × 4 ряда, между ними рамка. */
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final ClaimsPlugin plugin;

    public ClaimMenuGui(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Открывает меню на выбранной вкладке; номер страницы зажимается при отрисовке. */
    public void open(Player player, ClaimMenuModel.Tab tab, int page) {
        if (!plugin.config().menuEnabled) {
            plugin.messages().send(player, "menu-disabled");
            return;
        }
        if (!player.hasPermission("chrdkclaims.menu")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        MenuHolder holder = new MenuHolder();
        holder.tab = tab == null ? ClaimMenuModel.Tab.MINE : tab;
        holder.page = page;
        // Заголовок окна не зависит от вкладки: вкладки перерисовываются на месте,
        // а смена заголовка потребовала бы переоткрытия окна.
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.bind(inventory);
        render(player, holder);
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------ отрисовка --

    private void render(Player player, MenuHolder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }
        if (holder.tab == ClaimMenuModel.Tab.FOR_SALE && !plugin.config().menuShowForSale) {
            holder.tab = ClaimMenuModel.Tab.MINE;
        }
        List<Claim> claims = holder.tab == ClaimMenuModel.Tab.MINE
                ? plugin.service().ownedSorted(player.getName())
                : plugin.store().all();
        List<ClaimMenuModel.Entry> entries = ClaimMenuModel.sorted(
                ClaimMenuModel.view(claims, player.getName(), holder.tab),
                player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
        int pages = Pagination.pageCount(entries.size(), ClaimMenuModel.PAGE_SIZE);
        holder.page = Pagination.clampPage(holder.page, pages);
        holder.entries.clear();

        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        List<ClaimMenuModel.Entry> page = Pagination.slice(entries, holder.page,
                ClaimMenuModel.PAGE_SIZE);
        for (int index = 0; index < page.size() && index < CONTENT_SLOTS.length; index++) {
            ClaimMenuModel.Entry entry = page.get(index);
            int slot = CONTENT_SLOTS[index];
            holder.entries.put(slot, entry);
            inventory.setItem(slot, entryIcon(player, entry));
        }
        if (page.isEmpty()) {
            inventory.setItem(SLOT_EMPTY, emptyIcon(holder));
        }

        inventory.setItem(SLOT_INFO, infoIcon(player, holder, entries.size(), pages));
        if (holder.page > 1) {
            inventory.setItem(SLOT_PREV, item(Material.ARROW, "menu-prev-name",
                    "menu-prev-lore", "page", String.valueOf(holder.page - 1)));
        }
        if (holder.page < pages) {
            inventory.setItem(SLOT_NEXT, item(Material.ARROW, "menu-next-name",
                    "menu-next-lore", "page", String.valueOf(holder.page + 1)));
        }
        inventory.setItem(SLOT_TAB_MINE, tabIcon(ClaimMenuModel.Tab.MINE,
                holder.tab == ClaimMenuModel.Tab.MINE));
        if (plugin.config().menuShowForSale) {
            inventory.setItem(SLOT_TAB_SALE, tabIcon(ClaimMenuModel.Tab.FOR_SALE,
                    holder.tab == ClaimMenuModel.Tab.FOR_SALE));
        }
        inventory.setItem(SLOT_TAB_ACCESS, tabIcon(ClaimMenuModel.Tab.ACCESSIBLE, holder.tab == ClaimMenuModel.Tab.ACCESSIBLE));
        inventory.setItem(SLOT_CLAN, item(Material.WHITE_BANNER, "menu-clan-name", "menu-clan-lore"));
        inventory.setItem(SLOT_CLOSE, item(Material.BARRIER, "menu-close-name", null));
    }

    private ItemStack filler() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(" "));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack infoIcon(Player player, MenuHolder holder, int total, int pages) {
        int limit = plugin.service().limitOf(player.getName());
        int treasury = plugin.store().treasuryOf(player.getName());
        int own = plugin.store().count(player.getName());
        return item(Material.BOOK, "menu-info-name", "finance.menu-info-lore",
                "tab", plugin.messages().plain(ClaimMenuModel.titleKey(holder.tab)),
                "count", String.valueOf(total),
                "page", String.valueOf(holder.page),
                "pages", String.valueOf(pages),
                "limit", String.valueOf(limit),
                "free", String.valueOf(Math.max(0, limit - own)),
                "treasury", String.valueOf(treasury), "physical", DiamondItems.count(player.getInventory()));
    }

    private ItemStack emptyIcon(MenuHolder holder) {
        return item(Material.PAPER,
                holder.tab == ClaimMenuModel.Tab.FOR_SALE ? "menu-empty-sale" : "menu-empty-mine",
                null);
    }

    private ItemStack entryIcon(Player player, ClaimMenuModel.Entry entry) {
        Material material;
        if (entry.mine()) {
            material = entry.world().equals(player.getWorld().getName())
                    ? Material.GRASS_BLOCK
                    : Material.MAP;
        } else {
            material = entry.salePrice() > 0 ? Material.GOLD_INGOT : Material.PAPER;
        }
        Object sale = entry.salePrice() > 0
                ? plugin.messages().mini("list-sale", "price", String.valueOf(entry.salePrice()))
                : "";
        return item(material, "menu-entry-name", "menu-entry-lore",
                "world", entry.world(),
                "x", String.valueOf(entry.x()),
                "z", String.valueOf(entry.z()),
                "owner", entry.owner(),
                "chunks", plugin.store().territory(plugin.store().claimAt(entry.world(), entry.x(), entry.z())).size(),
                "paid", plugin.service().formatDate(entry.paidUntil()),
                "sale", sale);
    }

    private ItemStack tabIcon(ClaimMenuModel.Tab tab, boolean selected) {
        boolean mine = tab == ClaimMenuModel.Tab.MINE;
        if (tab == ClaimMenuModel.Tab.ACCESSIBLE) {
            ItemStack stack = item(Material.OAK_DOOR, "menu-tab-access-name", "menu-tab-access-lore");
            ItemMeta meta = stack.getItemMeta();
            if (selected && meta != null) { meta.setEnchantmentGlintOverride(true); stack.setItemMeta(meta); }
            return stack;
        }
        ItemStack stack = item(mine ? Material.MAP : Material.GOLD_INGOT,
                mine ? "menu-tab-mine-name" : "menu-tab-sale-name",
                mine ? "menu-tab-mine-lore" : "menu-tab-sale-lore");
        ItemMeta meta = stack.getItemMeta();
        if (selected && meta != null) {
            // Подсветка выбранной вкладки без «фальшивого» зачарования в подсказке.
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(Material material, String nameKey, String loreKey,
                           Object... placeholders) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey, placeholders));
            if (loreKey != null) {
                meta.lore(plugin.messages().getList(loreKey, placeholders));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // -------------------------------------------------------- обработчики --

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.service().isAllowed(player)) { player.closeInventory(); return; }
        int slot = event.getSlot();
        if (slot == SLOT_CLAN) {
            boolean clanMenu = event.isRightClick();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory(); player.performCommand(clanMenu ? "clan menu" : "claim clan");
            });
            return;
        }
        if (slot == SLOT_CLOSE) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }
        boolean left = event.isLeftClick();
        boolean right = event.isRightClick();
        if (slot == SLOT_TAB_MINE || slot == SLOT_TAB_SALE || slot == SLOT_TAB_ACCESS) {
            ClaimMenuModel.Tab target = slot == SLOT_TAB_MINE
                    ? ClaimMenuModel.Tab.MINE
                    : slot == SLOT_TAB_ACCESS ? ClaimMenuModel.Tab.ACCESSIBLE : ClaimMenuModel.Tab.FOR_SALE;
            if (target == holder.tab) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                holder.tab = target;
                holder.page = 1;
                render(player, holder);
            });
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            if (!left && !right) {
                return;
            }
            int delta = slot == SLOT_NEXT ? 1 : -1;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                holder.page += delta;
                render(player, holder);
            });
            return;
        }
        ClaimMenuModel.Entry entry = holder.entries.get(slot);
        if (entry == null) {
            return;
        }
        if (right) {
            showInfo(player, entry);
            return;
        }
        if (!left) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> teleport(player, entry));
    }

    /** Правый клик: короткая сводка по карточке, меню остаётся открытым. */
    private void showInfo(Player player, ClaimMenuModel.Entry entry) {
        Claim claim = plugin.store().claimAt(entry.world(), entry.x(), entry.z());
        ClaimMenuModel.Entry actual = claim == null
                ? entry
                : new ClaimMenuModel.Entry(claim.world, claim.x, claim.z, claim.owner,
                        claim.paidUntil, claim.salePrice, claim.isOwner(player.getName()));
        Object sale = actual.salePrice() > 0
                ? plugin.messages().mini("list-sale", "price", String.valueOf(actual.salePrice()))
                : "";
        plugin.messages().send(player, "menu-info-line",
                "world", actual.world(),
                "x", String.valueOf(actual.x()),
                "z", String.valueOf(actual.z()),
                "owner", actual.owner(),
                "paid", plugin.service().formatDate(actual.paidUntil()),
                "sale", sale);
    }

    /** Левый клик: закрываем меню и просим телепорт — приват мог измениться за время показа. */
    private void teleport(Player player, ClaimMenuModel.Entry entry) {
        Claim claim = plugin.store().claimAt(entry.world(), entry.x(), entry.z());
        player.closeInventory();
        if (claim == null) {
            plugin.messages().send(player, "menu-stale");
            return;
        }
        plugin.teleports().request(player, claim);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркер-держатель меню: вкладка, страница и карточки текущей страницы. */
    private static final class MenuHolder implements InventoryHolder {

        private Inventory inventory;
        private ClaimMenuModel.Tab tab = ClaimMenuModel.Tab.MINE;
        private int page = 1;
        private final Map<Integer, ClaimMenuModel.Entry> entries = new HashMap<>();

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
