package ru.cherdakmd.chrdktreasures;

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
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Витрина коллекции (`/treasures`).
 *
 * <p>Главный экран — 14 регионов и мифическая витрина. В регионе четыре
 * находки: найденные показываются настоящими предметами с полным лором и
 * счётчиком, ненайденные — серым «???» с подсказкой о редкости. Мифические
 * находки не привязаны к региону и вынесены отдельной страницей.</p>
 *
 * <p>Клики по верхнему инвентарю всегда отменяются, драг запрещён: витрина
 * ничего не выдаёт и не даёт унести иконки. Переключатели звука, частиц и
 * сообщений меняют только оформление находок и хранятся в профиле игрока.</p>
 */
public final class TreasuresGui implements Listener {

    private static final int[] REGION_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25};
    private static final int MYTHIC_SLOT = 31;
    private static final int SUMMARY_SLOT = 45;
    private static final int TOP_SLOT = 46;
    private static final int SOUND_SLOT = 48;
    private static final int PARTICLE_SLOT = 49;
    private static final int ANNOUNCE_SLOT = 50;
    private static final int CLOSE_SLOT = 53;
    /**
     * Сетка страницы региона и мифической витрины: два ряда по семь слотов.
     * Классические находки идут первыми, за ними — вещи арсенала.
     */
    private static final int[] ITEM_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int PAGE_BACK = 45;
    private static final int PAGE_CLOSE = 53;
    private static final int TOP_LIMIT = 45;

    private enum View {
        MAIN,
        REGION,
        MYTHIC,
        TOP
    }

    /** Владелец инвентаря: защищает от подмены и чужих кликов. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final View view;
        private final TreasureRegion region;
        private Inventory inventory;

        Holder(UUID owner, View view, TreasureRegion region) {
            this.owner = owner;
            this.view = view;
            this.region = region;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TreasuresPlugin plugin;
    private final TreasureService service;

    public TreasuresGui(TreasuresPlugin plugin, TreasureService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* --------------------------------- главный -------------------------------- */

    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "treasures-disabled");
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), View.MAIN, null);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("menu-title"));
        TreasureRegion[] regions = TreasureRegion.values();
        for (int index = 0; index < Math.min(REGION_SLOTS.length, regions.length); index++) {
            holder.inventory.setItem(REGION_SLOTS[index], regionIcon(player, regions[index]));
        }
        holder.inventory.setItem(MYTHIC_SLOT, mythicIcon(player));
        holder.inventory.setItem(SUMMARY_SLOT, summary(player));
        holder.inventory.setItem(TOP_SLOT, button(Material.GOLD_INGOT, "menu-top-name",
                plugin.messages().raw("menu-top-lore")));
        holder.inventory.setItem(SOUND_SLOT, toggle(player,
                service.store().sounds(key(player)), "menu-sounds-name"));
        holder.inventory.setItem(PARTICLE_SLOT, toggle(player,
                service.store().particles(key(player)), "menu-particles-name"));
        holder.inventory.setItem(ANNOUNCE_SLOT, toggle(player,
                service.store().announce(key(player)), "menu-announce-name"));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    private ItemStack regionIcon(Player player, TreasureRegion region) {
        List<TreasureItemDef> items = TreasureRegistry.region(region);
        int found = 0;
        for (TreasureItemDef def : items) {
            if (service.hasItem(player, def.id())) {
                found++;
            }
        }
        int total = items.size();
        ItemStack item = new ItemStack(materialOf(region));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("menu-region-name",
                "region", Messages.trusted(plugin.messages().raw("region." + region.id())),
                "found", String.valueOf(found),
                "total", String.valueOf(total)));
        meta.lore(List.of(
                plugin.messages().get("menu-region-lore",
                        "found", String.valueOf(found),
                        "total", String.valueOf(total)),
                plugin.messages().get("menu-region-click")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack mythicIcon(Player player) {
        List<TreasureItemDef> items = TreasureRegistry.mythical();
        int found = 0;
        for (TreasureItemDef def : items) {
            if (service.hasItem(player, def.id())) {
                found++;
            }
        }
        int total = items.size();
        ItemStack item = new ItemStack(Material.DRAGON_EGG);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("menu-mythic-name",
                "found", String.valueOf(found),
                "total", String.valueOf(total)));
        meta.lore(List.of(plugin.messages().get("menu-mythic-lore",
                "found", String.valueOf(found),
                "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summary(Player player) {
        long mined = service.mined(player);
        int found = service.foundItems(player);
        int total = TreasureRegistry.size();
        String next = service.nextTierHint(player);
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("menu-summary-name",
                "found", String.valueOf(found),
                "total", String.valueOf(total)));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-summary-mined", "mined", String.valueOf(mined)));
        lore.add(plugin.messages().get("menu-summary-found",
                "found", String.valueOf(service.found(player))));
        lore.add(plugin.messages().get("menu-summary-collection",
                "found", String.valueOf(found),
                "total", String.valueOf(total)));
        lore.add(plugin.messages().get("menu-summary-rank",
                "rank", Messages.trusted(plugin.messages().raw(
                        "rank." + service.rank(player).id()))));
        lore.add(plugin.messages().get("menu-summary-streak",
                "streak", String.valueOf(service.streak(player)),
                "best", String.valueOf(service.bestStreak(player))));
        lore.add(next.isBlank()
                ? plugin.messages().get("menu-summary-next-none")
                : plugin.messages().get("menu-summary-next", "hint", Messages.trusted(next)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack toggle(Player player, boolean enabled, String key) {
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get(key));
        meta.lore(List.of(plugin.messages().get(enabled ? "menu-toggle-on" : "menu-toggle-off")));
        item.setItemMeta(meta);
        return item;
    }

    /* --------------------------------- регион --------------------------------- */

    public void openRegion(Player player, TreasureRegion region) {
        if (player == null || region == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), View.REGION, region);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("region-title",
                        "region", Messages.trusted(plugin.messages().raw("region." + region.id()))));
        List<TreasureItemDef> items = TreasureRegistry.region(region);
        for (int index = 0; index < Math.min(ITEM_SLOTS.length, items.size()); index++) {
            holder.inventory.setItem(ITEM_SLOTS[index], itemIcon(player, items.get(index)));
        }
        holder.inventory.setItem(PAGE_BACK, button(Material.ARROW, "menu-back", null));
        holder.inventory.setItem(PAGE_CLOSE, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /* -------------------------------- мифические ------------------------------- */

    public void openMythical(Player player) {
        if (player == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), View.MYTHIC, null);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("mythic-title"));
        List<TreasureItemDef> items = TreasureRegistry.mythical();
        for (int index = 0; index < Math.min(ITEM_SLOTS.length, items.size()); index++) {
            holder.inventory.setItem(ITEM_SLOTS[index], itemIcon(player, items.get(index)));
        }
        holder.inventory.setItem(PAGE_BACK, button(Material.ARROW, "menu-back", null));
        holder.inventory.setItem(PAGE_CLOSE, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /** Иконка находки: найденная — настоящий предмет, иначе закрытая карточка. */
    private ItemStack itemIcon(Player player, TreasureItemDef def) {
        if (def == null) {
            return new ItemStack(Material.BARRIER);
        }
        int count = service.itemCount(player, def.id());
        if (count <= 0) {
            ItemStack locked = new ItemStack(Material.GRAY_DYE);
            ItemMeta meta = locked.getItemMeta();
            if (meta == null) {
                return locked;
            }
            meta.displayName(plugin.messages().get("menu-locked-name",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + def.tier().id()))));
            meta.lore(List.of(plugin.messages().get("menu-locked-lore")));
            locked.setItemMeta(meta);
            return locked;
        }
        ItemStack item = plugin.items().create(def);
        if (item == null) {
            return new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(plugin.messages().get("item-found", "count", String.valueOf(count)));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /* ----------------------------------- топ ---------------------------------- */

    public void openTop(Player player) {
        if (player == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), View.TOP, null);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("top-title"));
        List<TreasureService.Entry> top = service.top();
        if (top.isEmpty()) {
            holder.inventory.setItem(22, button(Material.PAPER, "top-empty", null));
        }
        int place = 1;
        for (TreasureService.Entry entry : top) {
            if (place > TOP_LIMIT) {
                break;
            }
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta == null) {
                continue;
            }
            meta.displayName(plugin.messages().get("top-entry-name",
                    "place", String.valueOf(place), "name", entry.name()));
            meta.lore(List.of(plugin.messages().get("top-entry-lore",
                    "found", String.valueOf(entry.found()),
                    "collection", String.valueOf(entry.items()),
                    "total", String.valueOf(TreasureRegistry.size()))));
            head.setItemMeta(meta);
            holder.inventory.setItem(place - 1, head);
            place++;
        }
        holder.inventory.setItem(PAGE_BACK, button(Material.ARROW, "menu-back", null));
        holder.inventory.setItem(PAGE_CLOSE, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /* ---------------------------------- клики --------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST)
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
        if (slot < 0 || slot >= holder.inventory.getSize()) {
            return;
        }
        switch (holder.view) {
            case MAIN -> clickMain(player, slot);
            case REGION, MYTHIC -> {
                if (slot == PAGE_BACK) {
                    open(player);
                } else if (slot == PAGE_CLOSE) {
                    player.closeInventory();
                }
            }
            case TOP -> {
                if (slot == PAGE_BACK) {
                    open(player);
                } else if (slot == PAGE_CLOSE) {
                    player.closeInventory();
                }
            }
        }
    }

    private void clickMain(Player player, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == TOP_SLOT) {
            openTop(player);
            return;
        }
        if (slot == MYTHIC_SLOT) {
            openMythical(player);
            return;
        }
        if (slot == SOUND_SLOT) {
            service.store().setSounds(key(player), !service.store().sounds(key(player)));
            open(player);
            return;
        }
        if (slot == PARTICLE_SLOT) {
            service.store().setParticles(key(player), !service.store().particles(key(player)));
            open(player);
            return;
        }
        if (slot == ANNOUNCE_SLOT) {
            service.store().setAnnounce(key(player), !service.store().announce(key(player)));
            open(player);
            return;
        }
        TreasureRegion[] regions = TreasureRegion.values();
        for (int index = 0; index < Math.min(REGION_SLOTS.length, regions.length); index++) {
            if (REGION_SLOTS[index] == slot) {
                openRegion(player, regions[index]);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /* -------------------------------- оформление ------------------------------- */

    private ItemStack button(Material material, String nameKey, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get(nameKey));
        if (lore != null && !lore.isBlank()) {
            meta.lore(List.of(Messages.format(lore)));
        }
        item.setItemMeta(meta);
        return item;
    }

    static Material materialOf(TreasureRegion region) {
        return switch (region) {
            case PLAINS -> Material.GRASS_BLOCK;
            case FOREST -> Material.OAK_SAPLING;
            case TAIGA -> Material.SPRUCE_SAPLING;
            case SNOW -> Material.SNOWBALL;
            case DESERT -> Material.SAND;
            case SAVANNA -> Material.ACACIA_SAPLING;
            case JUNGLE -> Material.JUNGLE_SAPLING;
            case SWAMP -> Material.LILY_PAD;
            case MOUNTAIN -> Material.STONE;
            case OCEAN -> Material.PRISMARINE_SHARD;
            case DEEP -> Material.DEEPSLATE;
            case DEEP_DARK -> Material.SCULK;
            case NETHER -> Material.NETHERRACK;
            case END -> Material.END_STONE;
        };
    }

    private static String key(Player player) {
        return player.getUniqueId().toString();
    }
}
