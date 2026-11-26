package ru.cherdakmd.chrdkskills;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Optional appearance for newly created crystals; never used to identify or redeem them. */
final class SkillCrystalVisuals {
    private SkillCrystalVisuals() { }

    static NamespacedKey model(SkillType skill) {
        return new NamespacedKey("chrdk_skills", "crystal/" + Objects.requireNonNull(skill).id());
    }

    static void applyNew(ItemMeta meta, SkillType skill, boolean enabled) {
        Objects.requireNonNull(meta);
        Objects.requireNonNull(skill);
        // Never replace another plugin's item model or legacy custom model data.
        if (enabled && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(model(skill));
    }
}
