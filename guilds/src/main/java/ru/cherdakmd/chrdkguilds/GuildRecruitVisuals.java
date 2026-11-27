package ru.cherdakmd.chrdkguilds;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for virtual recruitment previews and consent controls, never inventory items. */
final class GuildRecruitVisuals {
    enum Control {
        HEADING("heading"), EMPTY("empty"), OPEN("open"), CLOSED("closed"), OPEN_INVITED("open_invited"), CLOSED_INVITED("closed_invited"),
        TERMS("terms"), CONFIRM("confirm"), CANCEL("cancel"), BACK("back"), PREV("prev"), NEXT("next");
        private final String id;
        Control(String id) { this.id=id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_guilds", "menu/recruit/"+id); }
    }
    private GuildRecruitVisuals() { }
    static Control listing(boolean open,boolean invited) {
        return open ? invited ? Control.OPEN_INVITED : Control.OPEN : invited ? Control.CLOSED_INVITED : Control.CLOSED;
    }
    static Control button(String key) {
        return switch(key) {
            case "recruit-list-heading" -> Control.HEADING; case "recruit-empty" -> Control.EMPTY;
            case "recruit-confirm-button" -> Control.CONFIRM; case "recruit-cancel" -> Control.CANCEL;
            case "recruit-back" -> Control.BACK; case "recruit-previous" -> Control.PREV; case "recruit-next" -> Control.NEXT;
            default -> null;
        };
    }
    static void apply(ItemMeta meta,Control control,boolean enabled) {
        if (!enabled || meta==null || control==null || meta.getItemModel()!=null || meta.hasCustomModelData()) return;
        meta.setItemModel(control.model());
    }
}
