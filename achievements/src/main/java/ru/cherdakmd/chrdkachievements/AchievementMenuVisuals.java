package ru.cherdakmd.chrdkachievements;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Optional models for fresh virtual achievement GUI items; never changes awards or player items. */
final class AchievementMenuVisuals {
    enum Control {
        ATLAS("atlas"), PROGRESS("progress"), PREV("prev"), NEXT("next"), CLOSE("close");
        private final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_achievements", "menu/" + id); }
    }
    static NamespacedKey entry(AchievementDef.Category category, boolean unlocked) {
        return new NamespacedKey("chrdk_achievements", "menu/entry/" + category.id() + (unlocked ? "_unlocked" : "_locked"));
    }
    static NamespacedKey tab(String key, boolean active) {
        String id = switch (key) {
            case "tab-path" -> "path";
            case "tab-combat" -> "combat";
            case "tab-craft" -> "craft";
            case "tab-social" -> "social";
            case "tab-all" -> "all";
            default -> null;
        };
        return id == null ? null : new NamespacedKey("chrdk_achievements", "menu/tab/" + id + (active ? "_selected" : "_inactive"));
    }
    static Control page(String key) {
        return switch (key) {
            case "page-prev" -> Control.PREV;
            case "page-next" -> Control.NEXT;
            default -> null;
        };
    }
    static Control button(String key) {
        return switch (key) {
            case "atlas-title-lifetime" -> Control.ATLAS;
            case "gui-close" -> Control.CLOSE;
            default -> null;
        };
    }
    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(model);
        }
    }
    private AchievementMenuVisuals() { }
}
