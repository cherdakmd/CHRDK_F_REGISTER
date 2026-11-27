package ru.cherdakmd.diamondshop;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Editor controls only; configurable product previews keep their original metadata. */
final class ShopAdminVisuals {
    private static final Map<String,String> IDS=Map.ofEntries(
        Map.entry("admin.gui.empty","empty"),Map.entry("admin.gui.close-name","close"),Map.entry("admin.gui.save-name","save"),
        Map.entry("admin.gui.prev-name","prev"),Map.entry("admin.gui.next-name","next"),Map.entry("admin.gui.back-name","back"),
        Map.entry("admin.gui.restore-name","restore"),Map.entry("admin.gui.remove-name","remove"),Map.entry("admin.gui.remove-confirm","remove_confirm"),
        Map.entry("admin.gui.price-down-small","price_down_small"),Map.entry("admin.gui.price-down","price_down"),
        Map.entry("admin.gui.price-up","price_up"),Map.entry("admin.gui.price-up-small","price_up_small"),
        Map.entry("admin.gui.stock-down","stock_down"),Map.entry("admin.gui.stock-up","stock_up"),
        Map.entry("admin.gui.field-category","category"),Map.entry("admin.gui.tariff-down","tariff_down"),Map.entry("admin.gui.tariff-up","tariff_up"));
    private ShopAdminVisuals() { }
    static NamespacedKey key(String message,Material material) {
        String id="admin.gui.field-sell".equals(message)?(material==Material.LIME_DYE?"sell_enabled":"sell_disabled"):message==null?null:IDS.get(message);
        return id==null?null:new NamespacedKey("chrdk_diamondshop","menu/admin/"+id);
    }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) { if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model); }
}
