package ru.cherdakmd.chrdkenchants;

import org.bukkit.NamespacedKey;
import java.util.Map;
import java.util.Set;

/**
 * Pure selection only: not yet wired to item mutation or vanilla event handlers.
 * A null result means retain the existing appearance, not alter the book.
 */
final class BookVisualPolicy {
    // Snapshot of the 26.1 vanilla catalogue; compare with the actual 26.3 registry before release.
    static final Set<String> VANILLA = Set.of(
            "aqua_affinity", "bane_of_arthropods", "binding_curse", "blast_protection", "breach", "channeling",
            "density", "depth_strider", "efficiency", "feather_falling", "fire_aspect", "fire_protection", "flame",
            "fortune", "frost_walker", "impaling", "infinity", "knockback", "looting", "loyalty", "luck_of_the_sea",
            "lunge", "lure", "mending", "multishot", "piercing", "power", "projectile_protection", "protection",
            "punch", "quick_charge", "respiration", "riptide", "sharpness", "silk_touch", "smite", "soul_speed",
            "sweeping_edge", "swift_sneak", "thorns", "unbreaking", "vanishing_curse", "wind_burst");

    /** Map keys are exact custom IDs and full vanilla namespaced keys; levels must be valid. */
    static NamespacedKey select(Map<String, Integer> custom, Map<String, Integer> vanilla) {
        if (custom == null || vanilla == null || custom.size() > EnchantRegistry.knownCount() || vanilla.size() > VANILLA.size()) return null;
        for (var entry : custom.entrySet()) {
            var def = EnchantRegistry.byId(entry.getKey());
            if (def == null || !def.id().equals(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() < 1 || entry.getValue() > def.maxLevel()) return null;
        }
        for (var entry : vanilla.entrySet()) {
            var id = entry.getKey();
            if (id == null || !id.startsWith("minecraft:") || !VANILLA.contains(id.substring(10))
                    || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 255) return null;
        }
        if (custom.isEmpty() && vanilla.isEmpty()) return null;
        if (custom.size() == 1 && vanilla.isEmpty()) return key("custom/" + custom.keySet().iterator().next());
        if (custom.isEmpty() && vanilla.size() == 1) return key("vanilla/" + vanilla.keySet().iterator().next().substring(10));
        return key(custom.isEmpty() ? "mixed/vanilla" : vanilla.isEmpty() ? "mixed/custom" : "mixed/both");
    }

    private static NamespacedKey key(String path) { return new NamespacedKey("chrdk_enchants", "book/" + path); }
    private BookVisualPolicy() { }
}
