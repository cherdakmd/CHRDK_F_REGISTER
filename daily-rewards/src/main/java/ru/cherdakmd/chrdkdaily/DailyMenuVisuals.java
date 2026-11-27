package ru.cherdakmd.chrdkdaily;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual calendar icons only; never decorates a reward or player inventory item. */
final class DailyMenuVisuals {
    enum Icon {
        HEADER("header"), CLAIMED("claimed"), CLAIMED_TODAY("claimed_today"), TODAY("today"), LOCKED("locked");
        private final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_daily", "menu/" + id); }
    }
    static void apply(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(icon.model());
        }
    }
    private DailyMenuVisuals() { }
}
