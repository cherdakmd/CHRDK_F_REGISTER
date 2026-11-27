package ru.cherdakmd.diamondshop;

import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual quantity controls only; never applied to the traded item preview. */
final class ShopQuantityVisuals {
    private static final Set<String> IDS=Set.of("minus_64","minus_8","minus_1","plus_1","plus_8","plus_64","preset_1","preset_8","preset_16","preset_32","preset_64","minimum","total_buy","total_sell","confirm","unavailable","back","maximum");
    private ShopQuantityVisuals() { }
    static NamespacedKey key(String id) { return id==null||!IDS.contains(id)?null:new NamespacedKey("chrdk_diamondshop","menu/quantity/"+id); }
    static String delta(int amount) { return (amount<0?"minus_":"plus_")+Math.abs(amount); }
    static void apply(ItemMeta meta,String id,boolean enabled) { var model=key(id);if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model); }
}
