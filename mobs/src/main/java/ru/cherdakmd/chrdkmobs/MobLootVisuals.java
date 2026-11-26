package ru.cherdakmd.chrdkmobs;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Appearance for freshly created loot only; PDC remains the gameplay identity. */
final class MobLootVisuals {
    static NamespacedKey model(MobLootItems.Kind kind) {
        return new NamespacedKey("chrdk_mobs", "loot/" + java.util.Objects.requireNonNull(kind).id);
    }
    static void applyNew(ItemMeta meta, MobLootItems.Kind kind, boolean enabled) {
        if (enabled && meta != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(model(kind));
    }
    private MobLootVisuals() { }
}
