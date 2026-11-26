package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Presentation only for freshly constructed, non-transferable collection GUI icons. */
final class CollectionVisuals {
    enum Icon {
        UNKNOWN("unknown"), PREVIOUS("previous"), NEXT("next"),
        REWARDS("rewards"), PROGRESS("progress"), CLOSE("close");

        private final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_artifacts", "collection/" + id); }
    }

    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(icon.model());
    }

    static void entry(ItemMeta meta, ArtifactDef def, boolean found, boolean enabled) {
        if (!enabled || meta == null) return;
        if (found) ArtifactVisuals.applyNew(meta, def, true);
        else button(meta, Icon.UNKNOWN, true); // no id-specific image for undiscovered artifacts
    }

    private CollectionVisuals() { }
}
