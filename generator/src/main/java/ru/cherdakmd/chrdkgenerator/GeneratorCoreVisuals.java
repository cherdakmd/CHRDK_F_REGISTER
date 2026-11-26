package ru.cherdakmd.chrdkgenerator;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Visual-only model of a newly crafted core; never overwrites foreign appearance. */
final class GeneratorCoreVisuals {
    static final NamespacedKey MODEL = new NamespacedKey("chrdk_generator", "core");
    static void applyNew(ItemMeta meta, boolean enabled) {
        if (enabled && meta != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(MODEL);
    }
    private GeneratorCoreVisuals() { }
}
