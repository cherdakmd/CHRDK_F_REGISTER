package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models exclusively for virtual buttons in Fishing's own navigation GUI. */
final class FishMenuVisuals {
    enum Section {
        ALBUM("album", false), SALE("sale", false), ROD("rod", false),
        BAIT_SHOP("bait_shop", true), BAIT_EQUIP("bait_equip", true),
        ORDERS("orders", true), COOKING("cooking", false);

        final String id;
        final boolean canBeUnavailable;
        Section(String id, boolean canBeUnavailable) {
            this.id = id;
            this.canBeUnavailable = canBeUnavailable;
        }
        NamespacedKey model(boolean available) {
            String state = !available && canBeUnavailable ? "_unavailable" : "";
            return new NamespacedKey("chrdk_fishing", "menu/main/" + id + state);
        }
    }

    static void apply(ItemMeta meta, Section section, boolean available, boolean enabled) {
        if (enabled && meta != null && section != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(section.model(available));
    }

    private FishMenuVisuals() { }
}
