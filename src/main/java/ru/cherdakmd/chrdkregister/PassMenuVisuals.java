package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Display-only icons; this menu never contains password digits or secrets. */
final class PassMenuVisuals {
    private PassMenuVisuals() { }
    static NamespacedKey model(PassMenuService.Action action) {
        return action == null ? null : new NamespacedKey("chrdk_core", "menu/pass/"+action.name().toLowerCase(Locale.ROOT));
    }
    static void apply(ItemMeta meta,PassMenuService.Action action,boolean enabled) {
        var model=model(action);
        if (enabled && meta!=null && model!=null && meta.getItemModel()==null && !meta.hasCustomModelData()) meta.setItemModel(model);
    }
}
