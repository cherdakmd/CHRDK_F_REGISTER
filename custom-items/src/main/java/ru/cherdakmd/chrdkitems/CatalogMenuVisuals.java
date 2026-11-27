package ru.cherdakmd.chrdkitems;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Only the newly constructed catalog navigation icons; never the catalog item previews. */
final class CatalogMenuVisuals {
    enum Control {
        ALL("all"), WEAPON("weapon"), ARMOR("armor"), TOOL("tool"), CONSUMABLE("consumable"),
        PREV("prev"), NEXT("next"), CLOSE("close");
        final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model(boolean selected) {
            return new NamespacedKey("chrdk_items", "menu/catalog/" + id
                    + (ordinal() <= CONSUMABLE.ordinal() ? (selected ? "_selected" : "_normal") : ""));
        }
    }
    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (!enabled || meta == null || model == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(model);
    }
}
