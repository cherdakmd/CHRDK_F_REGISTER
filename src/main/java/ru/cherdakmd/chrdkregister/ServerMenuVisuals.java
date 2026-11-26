package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Optional, display-only models for known stock /menu category and navigation icons. */
final class ServerMenuVisuals {
    private ServerMenuVisuals() { }

    static NamespacedKey category(String id, Material material) {
        if (id == null || material == null) return null;
        Material expected = switch (id) {
            case "account" -> Material.PAPER;
            case "progress" -> Material.EXPERIENCE_BOTTLE;
            case "economy" -> Material.DIAMOND;
            case "world" -> Material.GRASS_BLOCK;
            case "gear" -> Material.ANVIL;
            case "social" -> Material.BELL;
            default -> null;
        };
        return material == expected ? key("category/" + id) : null;
    }

    static NamespacedKey back(Material material) {
        return material == Material.ARROW ? key("navigation/back") : null;
    }

    static NamespacedKey close(Material material) {
        return material == Material.BARRIER ? key("navigation/close") : null;
    }

    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(model);
    }

    private static NamespacedKey key(String path) { return new NamespacedKey("chrdk_menu", path); }
}
