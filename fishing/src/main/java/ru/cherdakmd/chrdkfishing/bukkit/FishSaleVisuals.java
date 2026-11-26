package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Decorates freshly constructed virtual icons only, never the basket's source items. */
final class FishSaleVisuals {
    enum Icon {
        HELP("help"), CLEAR("clear"), EMPTY("empty"), SELL("sell"), BACK("back"), CLOSE("close");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/sale/" + id); }
    }
    private static void apply(ItemMeta meta, NamespacedKey key, boolean enabled) {
        if (enabled && meta != null && key != null && meta.getItemModel() == null && !meta.hasCustomModelData()) meta.setItemModel(key);
    }
    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && icon != null) apply(meta, icon.model(), true);
    }
    static void fish(ItemMeta meta, String species, boolean enabled) {
        if (enabled) apply(meta, FishingVisuals.fish(species), true);
    }
    static ItemStack buttonIcon(ItemStack virtual, Icon icon, boolean enabled) {
        if (enabled) { var meta = virtual.getItemMeta(); button(meta, icon, true); if (meta != null) virtual.setItemMeta(meta); }
        return virtual;
    }
    static ItemStack fishIcon(ItemStack virtual, String species, boolean enabled) {
        if (enabled) { var meta = virtual.getItemMeta(); fish(meta, species, true); if (meta != null) virtual.setItemMeta(meta); }
        return virtual;
    }
    private FishSaleVisuals() { }
}
