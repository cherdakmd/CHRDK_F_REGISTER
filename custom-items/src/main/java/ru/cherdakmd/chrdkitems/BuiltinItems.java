package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in identities are fixed and cannot be overridden by user catalog entries. */
final class BuiltinItems {
    static final String MUD_SHOVEL = "mud_shovel";
    static final ItemCatalog.Definition SHOVEL = new ItemCatalog.Definition(MUD_SHOVEL,
            ItemCatalog.Category.TOOL, Material.IRON_SHOVEL, "chrdk_items:item/mud_shovel",
            "Лопата в говне", List.of("Пока в основной руке: защита от отравления, скорость I и регенерация I."));

    static Map<String, ItemCatalog.Definition> merge(Map<String, ItemCatalog.Definition> configured) {
        if (configured.containsKey(MUD_SHOVEL)) throw new IllegalArgumentException("catalog.yml: reserved item id " + MUD_SHOVEL);
        var result = new LinkedHashMap<>(configured);
        result.put(MUD_SHOVEL, SHOVEL);
        return Map.copyOf(result);
    }
    static boolean isMudShovel(ItemCatalog.Definition def) {
        return def != null && MUD_SHOVEL.equals(def.id()) && def.category() == SHOVEL.category()
                && def.material() == SHOVEL.material() && def.model().equals(SHOVEL.model());
    }
}
