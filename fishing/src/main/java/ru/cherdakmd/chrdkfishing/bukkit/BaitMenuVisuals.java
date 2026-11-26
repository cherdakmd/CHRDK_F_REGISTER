package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkfishing.BaitCatalog;

/** Visual-only models for virtual controls in our bait menus, never real bait. */
final class BaitMenuVisuals {
    enum Icon {
        CONFIRM("confirm"), BACK("back"), CLOSE("close"), HELP("help");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/bait/" + id); }
    }
    static NamespacedKey bait(String id, boolean selected) {
        if (BaitCatalog.byId(id) == null) throw new IllegalArgumentException("unknown bait model");
        return new NamespacedKey("chrdk_fishing", "menu/bait/" + id + (selected ? "_selected" : ""));
    }
    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(icon.model());
    }
    static void bait(ItemMeta meta, String id, boolean selected, boolean enabled) {
        if (enabled && meta != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(bait(id, selected));
    }
    static org.bukkit.inventory.ItemStack buttonIcon(Icon icon, boolean enabled, org.bukkit.Material material, String name, String... lore) {
        var item = FishMainGui.icon(material, name, lore);
        if (enabled) { var meta = item.getItemMeta(); button(meta, icon, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    static org.bukkit.inventory.ItemStack baitIcon(String id, boolean selected, boolean enabled, org.bukkit.Material material, String name, String... lore) {
        var item = FishMainGui.icon(material, name, lore);
        if (enabled) { var meta = item.getItemMeta(); bait(meta, id, selected, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    private BaitMenuVisuals() { }
}
