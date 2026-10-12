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
 * Инвентарная «книга» всех 30 чар: иконки по группам, редкость, применимость, конфликты.
 * Справочный режим — клики ничего не выдают.
 */
public final class EnchantGui implements Listener {

    private static final int SIZE = 54;

    private final EnchantsPlugin plugin;

    public EnchantGui(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        EnchantsHolder holder = new EnchantsHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("gui-title"));
        holder.bind(inventory);
        List<EnchantDef> all = EnchantRegistry.all();
        for (int slot = 0; slot < Math.min(all.size(), 45); slot++) {
            inventory.setItem(slot, enchantIcon(all.get(slot)));
        }
        ItemStack footer = new ItemStack(Material.BOOK);
        ItemMeta footerMeta = footer.getItemMeta();
        if (footerMeta != null) {
            footerMeta.displayName(plugin.messages().get("gui-title"));
            footerMeta.lore(List.of(plugin.messages().get("gui-footer")));
            footer.setItemMeta(footerMeta);
        }
        inventory.setItem(SIZE - 5, footer);
        player.openInventory(inventory);
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
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get("lore-line",
                    "name", plugin.messages().raw("en." + def.id() + ".name"),
                    "levelroman", EnchantCodec.roman(def.maxLevel())));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
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
        if (!(event.getInventory().getHolder() instanceof EnchantsHolder)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EnchantsHolder) {
            event.setCancelled(true);
        }
    }

    private static final class EnchantsHolder implements InventoryHolder {

        private Inventory inventory;

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
