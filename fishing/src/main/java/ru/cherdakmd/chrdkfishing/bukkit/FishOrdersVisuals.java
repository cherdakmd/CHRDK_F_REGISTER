package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Only virtual order-menu items. Never touches the fish in the player's inventory. */
final class FishOrdersVisuals {
    enum Icon {
        ORDER_AVAILABLE("order_available"), ORDER_SELECTED("order_selected"), ORDER_DONE("order_done"),
        HELP("help"), CONFIRM("confirm"), BACK("back"), CLOSE("close");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/orders/" + id); }
    }
    static Icon order(boolean done, boolean selected) {
        return done ? Icon.ORDER_DONE : selected ? Icon.ORDER_SELECTED : Icon.ORDER_AVAILABLE;
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
    static ItemStack buttonIcon(Icon icon, boolean enabled, Material material, String name, String... lore) {
        var item = FishMainGui.icon(material, name, lore);
        if (enabled) { var meta = item.getItemMeta(); button(meta, icon, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    static ItemStack fishIcon(String species, boolean enabled, String name, String... lore) {
        var item = FishMainGui.icon(Material.PAPER, name, lore);
        if (enabled) { var meta = item.getItemMeta(); fish(meta, species, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    private FishOrdersVisuals() { }
}
