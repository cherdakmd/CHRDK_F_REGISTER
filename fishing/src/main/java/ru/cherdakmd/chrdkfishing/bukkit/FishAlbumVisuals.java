package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models only for virtual album entries and controls, never real fish. */
final class FishAlbumVisuals {
    enum Icon {
        UNKNOWN("unknown"), PREVIOUS("previous"), NEXT("next"), BACK("back"), PAGE("page"),
        REWARD_AVAILABLE("reward_available"), REWARD_WAITING("reward_waiting");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/album/" + id); }
    }

    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(icon.model());
    }

    static void entry(ItemMeta meta, String species, boolean found, boolean enabled) {
        if (!enabled || meta == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        // All undiscovered entries have the same model; never leak their species.
        meta.setItemModel(found ? FishingVisuals.fish(species) : Icon.UNKNOWN.model());
    }

    private FishAlbumVisuals() { }
}
