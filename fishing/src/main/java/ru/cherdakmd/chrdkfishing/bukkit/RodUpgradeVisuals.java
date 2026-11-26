package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for virtual controls, not for the rod or snapshot in the player's inventory. */
final class RodUpgradeVisuals {
    enum Icon {
        HELP("help"), EMPTY("empty"), SELECTED("selected"), MAXIMUM("maximum"),
        PREPARE("prepare"), CONFIRM("confirm"), BACK("back"), CLOSE("close");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/rod/" + id); }
    }
    static NamespacedKey selected(int level) {
        if (level < 1 || level > 10) throw new IllegalArgumentException("rod level");
        return level == 1 ? Icon.SELECTED.model() : FishingVisuals.rod(level);
    }
    private static void apply(ItemMeta meta, NamespacedKey key, boolean enabled) {
        if (enabled && meta != null && key != null && meta.getItemModel() == null && !meta.hasCustomModelData()) meta.setItemModel(key);
    }
    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && icon != null) apply(meta, icon.model(), true);
    }
    static void rod(ItemMeta meta, int level, boolean enabled) {
        if (enabled) apply(meta, selected(level), true);
    }
    static ItemStack buttonIcon(ItemStack virtual, Icon icon, boolean enabled) {
        if (enabled) { var meta = virtual.getItemMeta(); button(meta, icon, true); if (meta != null) virtual.setItemMeta(meta); }
        return virtual;
    }
    static ItemStack rodIcon(ItemStack virtual, int level, boolean enabled) {
        if (enabled) { var meta = virtual.getItemMeta(); rod(meta, level, true); if (meta != null) virtual.setItemMeta(meta); }
        return virtual;
    }
    private RodUpgradeVisuals() { }
}
