package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Appearance of newly created artifacts; not used to authenticate or identify them. */
final class ArtifactVisuals {
    private ArtifactVisuals() { }

    static NamespacedKey model(ArtifactDef def) {
        if (def == null || !def.equals(ArtifactRegistry.byId(def.id())))
            throw new IllegalArgumentException("unknown artifact");
        return new NamespacedKey("chrdk_artifacts", "artifact/" + def.id());
    }

    static void applyNew(ItemMeta meta, ArtifactDef def, boolean enabled) {
        if (!enabled || meta == null || def == null) return;
        // No foreign model, legacy custom model data, PDC, lore or traits are changed.
        if (meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(model(def));
    }
}
