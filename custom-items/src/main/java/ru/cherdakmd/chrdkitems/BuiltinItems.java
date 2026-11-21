package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in identities are fixed and cannot be overridden by user catalog entries. */
final class BuiltinItems {
    static final String MUD_SHOVEL = "mud_shovel";
    static final ItemCatalog.Definition SHOVEL = tool(MUD_SHOVEL, Material.IRON_SHOVEL, "Лопата в говне", "Основная рука: защита от Poison, скорость I и регенерация I.");
    static final ItemCatalog.Definition LITTER = tool("litter_scoop", Material.IRON_SHOVEL, "Лопатка для лотка", "Основная рука: замедление I и ночное зрение I.");
    static final ItemCatalog.Definition CAT_FINDER = tool("cat_finder", Material.WOODEN_HOE, "Кошачий следопыт", "Правый клик: ищет ближайшую кошку в радиусе 16 блоков.");
    static final ItemCatalog.Definition SPAWN_GUIDE = tool("spawn_guide", Material.STONE_AXE, "Топор путеводителя", "Правый клик: показывает координаты спавна текущего мира.");
    static final ItemCatalog.Definition WEATHER_GAUGE = tool("weather_gauge", Material.IRON_PICKAXE, "Погодная кирка", "Правый клик: показывает погоду и время до её смены.");
    static final List<ItemCatalog.Definition> ALL = List.of(SHOVEL, LITTER, CAT_FINDER, SPAWN_GUIDE, WEATHER_GAUGE);
    private static ItemCatalog.Definition tool(String id, Material material, String name, String description) {
        return new ItemCatalog.Definition(id, ItemCatalog.Category.TOOL, material, "chrdk_items:item/" + id, name, List.of(description));
    }
    static Map<String, ItemCatalog.Definition> merge(Map<String, ItemCatalog.Definition> configured) {
        var result = new LinkedHashMap<>(configured);
        for (var item : ALL) {
            if (configured.containsKey(item.id())) throw new IllegalArgumentException("catalog.yml: reserved item id " + item.id());
            result.put(item.id(), item);
        }
        return Map.copyOf(result);
    }
    static boolean isBuiltin(ItemCatalog.Definition def) {
        return def != null && ALL.stream().anyMatch(item -> item.id().equals(def.id())
                && item.category() == def.category() && item.material() == def.material() && item.model().equals(def.model()));
    }
    static boolean isMudShovel(ItemCatalog.Definition def) { return isBuiltin(def) && SHOVEL.id().equals(def.id()); }
}
