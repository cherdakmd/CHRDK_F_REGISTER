package ru.cherdakmd.chrdkguilds;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Fixed models for party controls, never player heads or inventory items. */
final class GuildPartyVisuals {
    enum Control {
        EMPTY("empty"), GROUP("group"), INVITE("invite"), ACCEPT("accept"), DECLINE("decline"),
        LEAVE("leave"), CLOSE("close"), PREV("prev"), BACK("back"), NEXT("next");
        private final String id;
        Control(String id) { this.id=id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_guilds", "menu/party/"+id); }
    }
    private GuildPartyVisuals() { }
    static void apply(ItemMeta meta, Control control, boolean enabled) {
        if (!enabled || meta == null || control == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(control.model());
    }
}
