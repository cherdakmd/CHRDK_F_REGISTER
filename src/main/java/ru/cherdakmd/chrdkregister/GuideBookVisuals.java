package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Only invoked while building a new guide; existing items are never scanned. */
final class GuideBookVisuals {
    static final NamespacedKey MODEL = new NamespacedKey("chrdk_core", "guide_book");
    static boolean applyNew(ItemMeta meta, boolean enabled) {
        if (!enabled || meta == null || meta.getItemModel() != null || meta.hasCustomModelData()) return false;
        meta.setItemModel(MODEL);
        return true;
    }
    private GuideBookVisuals() { }
}
