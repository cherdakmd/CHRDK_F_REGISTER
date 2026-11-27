package ru.cherdakmd.chrdkguilds;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Explicit virtual style previews; the selected clan frame is not modified. */
final class GuildStyleVisuals {
    enum Control {
        INFO("info"), BACK("back"), CLOSE("close"), OFF("off");
        private final String id;
        Control(String id) { this.id=id; }
        NamespacedKey model() { return key(id); }
    }
    private GuildStyleVisuals() { }
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_guilds", "menu/style/"+name); }
    static NamespacedKey category(String kind,boolean selected) {
        return kind!=null && GuildStyleGui.KINDS.contains(kind)?key("tab_"+kind+(selected?"_selected":"_normal")):null;
    }
    static NamespacedKey preset(String kind,String preset,boolean personal,boolean active) {
        if (kind==null || preset==null || !GuildStyleDefs.valid(kind,preset)) return null;
        return key("preset_"+kind+"_"+preset+"_"+(personal?(active?"both":"personal"):(active?"active":"normal")));
    }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) {
        if (!enabled || meta==null || model==null || meta.getItemModel()!=null || meta.hasCustomModelData()) return;
        meta.setItemModel(model);
    }
}
