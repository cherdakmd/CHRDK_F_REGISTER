package ru.cherdakmd.chrdkenchants;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Инвентарная «книга» всех чар: иконки по группам, редкость, применимость, конфликты.
 *
 * <p>1.4.128: чар стало 60, поэтому справочник разбит на страницы — светлые и
 * проклятия отдельно. Между страницами ходят стрелками в нижнем ряду, клики
 * ничего не выдают.</p>
 */
public final class EnchantGui implements Listener {

    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int FOOTER_SLOT = 49;

    private final EnchantsPlugin plugin;

    public EnchantGui(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Все страницы справочника: светлые чары, затем проклятия. */
    private static List<List<EnchantDef>> pages() {
        List<List<EnchantDef>> pages = new ArrayList<>();
        pages.add(EnchantRegistry.light());
        if (!EnchantRegistry.curses().isEmpty()) {
            pages.add(EnchantRegistry.curses());
        }
        return pages;
    }

    public void open(Player player) {
        open(player, 0);
    }

    /** Открывает конкретную страницу (0 — светлые, 1 — проклятия). */
    public void open(Player player, int requestedPage) {
        List<List<EnchantDef>> pages = pages();
        int page = Math.max(0, Math.min(pages.size() - 1, requestedPage));
        List<EnchantDef> items = pages.get(page);
        EnchantsHolder holder = new EnchantsHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("gui-title",
                        "page", page + 1, "pages", pages.size()));
        holder.bind(inventory);
        for (int slot = 0; slot < Math.min(items.size(), PAGE_SIZE); slot++) {
            inventory.setItem(slot, enchantIcon(items.get(slot)));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, navigation(Material.ARROW, "gui-prev"));
        }
        if (page < pages.size() - 1) {
            inventory.setItem(NEXT_SLOT, navigation(Material.ARROW, "gui-next"));
        }
        ItemStack footer = new ItemStack(Material.BOOK);
        ItemMeta footerMeta = footer.getItemMeta();
        if (footerMeta != null) {
            footerMeta.displayName(plugin.messages().get("gui-title",
                    "page", page + 1, "pages", pages.size()));
            footerMeta.lore(List.of(plugin.messages().get("gui-footer")));
            footer.setItemMeta(footerMeta);
        }
        inventory.setItem(FOOTER_SLOT, footer);
        player.openInventory(inventory);
    }

    /** Стрелка перехода между страницами. */
    private ItemStack navigation(Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(key));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Иконка чары: материал по группе применимости, имя, редкость, предметы, описание. */
    private ItemStack enchantIcon(EnchantDef def) {
        Material icon = switch (def.supports().stream().findFirst().orElse(ItemGroup.SWORD)) {
            case SWORD, AXE -> Material.IRON_SWORD;
            case BOW, CROSSBOW -> Material.BOW;
            case HELMET -> Material.IRON_HELMET;
            case CHESTPLATE -> Material.IRON_CHESTPLATE;
            case LEGGINGS -> Material.IRON_LEGGINGS;
            case BOOTS -> Material.IRON_BOOTS;
            case PICKAXE -> Material.IRON_PICKAXE;
            case SHOVEL -> Material.IRON_SHOVEL;
            case HOE -> Material.IRON_HOE;
        };
        ItemStack item = new ItemStack(def.curse() ? Material.SOUL_LANTERN : icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(
                    def.curse() ? "lore-line-curse" : "lore-line",
                    "name", plugin.messages().raw("en." + def.id() + ".name"),
                    "levelroman", EnchantCodec.roman(def.maxLevel())));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("gui-enchant-lore-polarity",
                    "polarity", plugin.messages().raw(def.polarity().labelKey())));
            lore.add(plugin.messages().get("gui-enchant-lore-rarity",
                    "rarity", plugin.messages().raw(def.rarity().labelKey()),
                    "maxlevel", def.maxLevel()));
            lore.add(plugin.messages().get("gui-enchant-lore-items",
                    "items", supportedLabel(def)));
            if (def.conflictGroup() != null) {
                lore.add(plugin.messages().get("info-conflicts",
                        "conflicts", conflictsLabel(def)));
            }
            lore.addAll(plugin.messages().getList("en." + def.id() + ".lore"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Ярлык предметов по множеству групп (текст из messages.yml, ключи item-*). */
    public String supportedLabel(EnchantDef def) {
        if (def.supports().size() == 4 && def.supports().contains(ItemGroup.HELMET)
                && def.supports().contains(ItemGroup.BOOTS)) {
            return plugin.messages().raw("item-armor");
        }
        if (def.supports().size() == ItemGroup.values().length) {
            return plugin.messages().raw("item-any");
        }
        if (def.supports().size() == 2 && def.supports().contains(ItemGroup.SWORD)
                && def.supports().contains(ItemGroup.AXE)) {
            return plugin.messages().raw("item-sword");
        }
        if (def.supports().size() == 2 && def.supports().contains(ItemGroup.BOW)
                && def.supports().contains(ItemGroup.CROSSBOW)) {
            return plugin.messages().raw("item-bow");
        }
        return plugin.messages().raw(def.supports().iterator().next().labelKey());
    }

    private String conflictsLabel(EnchantDef def) {
        List<String> conflicting = new ArrayList<>();
        for (EnchantDef other : EnchantRegistry.all()) {
            if (other.conflictGroup() != null
                    && other.conflictGroup().equals(def.conflictGroup())
                    && !other.id().equals(def.id())) {
                conflicting.add(plugin.messages().raw("en." + other.id() + ".name"));
            }
        }
        return String.join(", ", conflicting);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        // смена страницы — следующим тиком: открывать инвентарь внутри клика небезопасно
        if (event.getSlot() == NEXT_SLOT) {
            int page = holder.page() + 1;
            Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
        } else if (event.getSlot() == PREVIOUS_SLOT) {
            int page = holder.page() - 1;
            Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EnchantsHolder) {
            event.setCancelled(true);
        }
    }

    private static final class EnchantsHolder implements InventoryHolder {

        private final int page;
        private Inventory inventory;

        private EnchantsHolder(int page) {
            this.page = page;
        }

        private int page() {
            return page;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
