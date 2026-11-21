package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;

/** Fixed prototype mappings only; don't assign texture IDs to arbitrary catalog entries. */
final class PrototypeVisuals {
    static final int MODEL_DATA = 810001;

    static Integer modelData(ItemCatalog.Definition def) {
        if (matches(def, "mud_shovel", ItemCatalog.Category.TOOL, Material.IRON_SHOVEL)) return 810003;
        if (matches(def, "test_blade", ItemCatalog.Category.WEAPON, Material.IRON_SWORD)
                || matches(def, "test_pick", ItemCatalog.Category.TOOL, Material.IRON_PICKAXE)
                || matches(def, "test_helm", ItemCatalog.Category.ARMOR, Material.IRON_HELMET)
                || matches(def, "test_scroll", ItemCatalog.Category.CONSUMABLE, Material.PAPER)
                || matches(def, "test_sunblade", ItemCatalog.Category.WEAPON, Material.GOLDEN_SWORD)
                || matches(def, "test_moonblade", ItemCatalog.Category.WEAPON, Material.DIAMOND_SWORD)
                || matches(def, "test_chest", ItemCatalog.Category.ARMOR, Material.IRON_CHESTPLATE)
                || matches(def, "test_boots", ItemCatalog.Category.ARMOR, Material.IRON_BOOTS)
                || matches(def, "test_shovel", ItemCatalog.Category.TOOL, Material.IRON_SHOVEL)
                || matches(def, "test_hoe", ItemCatalog.Category.TOOL, Material.IRON_HOE)
                || matches(def, "test_rune", ItemCatalog.Category.CONSUMABLE, Material.AMETHYST_SHARD)
                || matches(def, "test_charm", ItemCatalog.Category.CONSUMABLE, Material.ECHO_SHARD)) return MODEL_DATA;
        return null;
    }
    private static boolean matches(ItemCatalog.Definition def, String id, ItemCatalog.Category category, Material material) {
        return def != null && id.equals(def.id()) && category == def.category() && material == def.material()
                && ("chrdk_items:item/" + id).equals(def.model());
    }
}
