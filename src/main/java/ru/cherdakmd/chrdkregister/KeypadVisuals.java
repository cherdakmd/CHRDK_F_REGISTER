package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Static control models only; never encode characters, layout, or password length. */
final class KeypadVisuals {
    enum Control { BACKSPACE, CLEAR, PAGE, CANCEL, CONFIRM, PROGRESS;
        NamespacedKey model() { return new NamespacedKey("chrdk_core", "menu/keypad/"+name().toLowerCase(java.util.Locale.ROOT)); }
    }
    private KeypadVisuals() { }
    static void apply(ItemMeta meta,Control control,boolean enabled) {
        if(enabled&&meta!=null&&control!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(control.model());
    }
}
