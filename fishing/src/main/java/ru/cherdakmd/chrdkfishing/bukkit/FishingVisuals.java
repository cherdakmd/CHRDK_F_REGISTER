package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkfishing.FishCatalog;

/** Opt-in item models. Visuals do not authenticate fish, rods, or their value. */
final class FishingVisuals {
    static NamespacedKey fish(String speciesId) {
        if (FishCatalog.byId(speciesId) == null) throw new IllegalArgumentException("unknown fish model");
        return new NamespacedKey("chrdk_fishing", "fish/species/" + speciesId);
    }
    static NamespacedKey rod(int level) {
        if (level < 2 || level > 10) throw new IllegalArgumentException("rod model level");
        return new NamespacedKey("chrdk_fishing", "rod/level_" + level);
    }
    static void applyRod(ItemMeta meta, int level, boolean enabled) {
        if (!enabled || level < 2) return;
        var existing = meta.getItemModel();
        // An unrelated pack may select an appearance via legacy/custom model data.
        if ((existing == null && !meta.hasCustomModelData()) || (existing != null && existing.getNamespace().equals("chrdk_fishing")
                && existing.getKey().matches("rod/level_[2-9]|rod/level_10")))
            meta.setItemModel(rod(level));
    }
    private FishingVisuals() { }
}
