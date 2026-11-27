package ru.cherdakmd.chrdkbp;

import org.bukkit.NamespacedKey;

/** Models apply only to the plugin's virtual expedition controls. */
final class BattlePassExpeditionVisuals {
    private BattlePassExpeditionVisuals() { }
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_battlepass", "menu/expedition/" + name); }
    static NamespacedKey license(boolean premium, boolean owned) {
        return key("license_" + (premium ? "premium" : "free") + (owned ? "_owned" : "_missing"));
    }
    static NamespacedKey effect(boolean enabled) { return key(enabled ? "effect_on" : "effect_off"); }
    static NamespacedKey title(String title) {
        if (title == null) return null;
        return switch (title) {
            case "off" -> key("title_off"); case "free" -> key("title_free"); case "premium" -> key("title_premium");
            default -> null;
        };
    }
    static NamespacedKey button(String name, String lore) {
        if (name == null) return null;
        return switch (name) {
            case "exp-mark-current" -> key("mark_current"); case "exp-marks" -> key("marks");
            case "exp-routes" -> key("routes"); case "exp-journal" -> key("journal");
            case "exp-labels" -> key("labels"); case "exp-claim" -> key("claim");
            case "guide-back" -> key("back"); case "atlas-back" -> key("journal_back");
            case "menu-close" -> key("close"); case "exp-route-create" -> key("route_create");
            case "exp-point-name" -> "exp-point-lore".equals(lore) ? key("waypoint")
                    : "exp-saved-route-lore".equals(lore) ? key("saved_route") : null;
            default -> null;
        };
    }
}
