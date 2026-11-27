package ru.cherdakmd.diamondshop;

import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual navigation only, not the products, their item metadata or money. */
final class ShopMarketVisuals {
    private static final Map<String,String> CONTROLS=Map.ofEntries(
        Map.entry("market.home.all","all"),Map.entry("market.home.sellall","sellall"),Map.entry("market.home.guide","guide"),
        Map.entry("market.home.promo","promo"),Map.entry("market.home.gift","gift"),Map.entry("market.home.gift-claimed","gift_claimed"),
        Map.entry("market.balance.name","balance"),Map.entry("trading.gui.close-name","close"),Map.entry("trading.gui.admin-edit","admin"),
        Map.entry("trading.gui.empty","empty"),Map.entry("market.category.previous","prev"),Map.entry("market.home.back","back"),Map.entry("trading.gui.next-name","next"));
    private ShopMarketVisuals() { }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_diamondshop","menu/market/"+id); }
    static NamespacedKey control(String message) { String id=message==null?null:CONTROLS.get(message); return id==null?null:key(id); }
    static NamespacedKey category(ShopCategory category,boolean selected) { return category==null?null:key("category_"+category.id()+(selected?"_selected":"")); }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) { if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model); }
}
