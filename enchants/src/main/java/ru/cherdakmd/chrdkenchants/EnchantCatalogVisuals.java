package ru.cherdakmd.chrdkenchants;

import java.util.Locale;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Pure visuals for virtual catalog entries and filters, not books held by players. */
final class EnchantCatalogVisuals {
    private static final Set<String> CONTROLS=Set.of("empty","prev","next","result","clear");
    private EnchantCatalogVisuals() { }
    private static NamespacedKey path(String name) { return new NamespacedKey("chrdk_enchants","menu/catalog/"+name); }
    private static String state(Enum<?> value) { return value==null?"all":value.name().toLowerCase(Locale.ROOT); }
    static NamespacedKey control(String name) { return name!=null&&CONTROLS.contains(name)?path(name):null; }
    static NamespacedKey group(ItemGroup group) { return path("group_"+state(group)); }
    static NamespacedKey rarity(EnchantDef.Rarity rarity) { return path("rarity_"+state(rarity)); }
    static NamespacedKey polarity(EnchantDef.Polarity polarity) { return path("polarity_"+state(polarity)); }
    static NamespacedKey hand(boolean on) { return path("hand_"+(on?"on":"off")); }
    static NamespacedKey sort(EnchantCatalog.Sort sort) { return sort==null?null:path("sort_"+state(sort)); }
    static NamespacedKey entry(EnchantDef def) {
        return def==null || EnchantRegistry.byId(def.id())!=def ? null : new NamespacedKey("chrdk_enchants","book/custom/"+def.id());
    }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) {
        if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData()) meta.setItemModel(model);
    }
}
