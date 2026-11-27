package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Item models for virtual honor editor controls, not player heads or real items. */
final class HonorMenuVisuals {
    enum Icon { DEFINITIONS, GRANTS, FILTER, REFRESH, PREV, NEXT, BACK, CLOSE,
        INFO, CREATE, DEFINITION, GRANT_ENTRY, NAME, FORMAT, BADGE, LEVEL,
        DESCRIPTION, JOIN, DELETE, PAGES, EMPTY_DEFINITIONS, EMPTY_GRANTS }
    private HonorMenuVisuals() { }
    static NamespacedKey key(Icon icon) {
        return icon == null ? null : new NamespacedKey("chrdk_core", "menu/honor/" + icon.name().toLowerCase(Locale.ROOT));
    }
    static void apply(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(key(icon));
        }
    }
}
