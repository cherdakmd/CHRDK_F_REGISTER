package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Visual rank preview only; prices and ownership remain in the existing services. */
final class RankMenuVisuals {
    enum Button { MY, TOP, RULES, CLOSE;
        NamespacedKey model() { return key(name().toLowerCase(java.util.Locale.ROOT)); }
    }
    private RankMenuVisuals() { }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_core", "menu/rank/"+id); }
    static NamespacedKey card(int level,int current) {
        if(level<1||level>5)return null;
        return key("level_"+level+"_"+(level==current?"current":level<current?"lower":"available"));
    }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) {
        if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model);
    }
}
