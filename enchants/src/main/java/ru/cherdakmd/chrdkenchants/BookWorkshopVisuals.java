package ru.cherdakmd.chrdkenchants;

import java.util.Locale;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual controls only: never called on the workshop's actual input slots. */
final class BookWorkshopVisuals {
    private static final Set<String> IDS=Set.of("instructions","left","right","cost_scribe","cost_cleanse","cost_combine","choice","selected","prev","next","combine","prepare","confirm","close");
    private BookWorkshopVisuals() { }
    static NamespacedKey key(String id) { return id==null||!IDS.contains(id)?null:new NamespacedKey("chrdk_enchants","menu/workshop/"+id); }
    static String cost(BookWorkshopGui.Mode mode) { return "cost_"+mode.name().toLowerCase(Locale.ROOT); }
    static void apply(ItemMeta meta,String id,boolean enabled) {
        var model=key(id);if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model);
    }
}
