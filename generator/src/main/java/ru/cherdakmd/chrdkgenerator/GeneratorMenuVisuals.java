package ru.cherdakmd.chrdkgenerator;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Fixed models for constructed GUI controls only; never inventory, bunker or fuel contents. */
final class GeneratorMenuVisuals {
    enum Control {
        REBIRTH("rebirth"), TAKE("take"), CLOSE("close"), FUEL_HELP("fuel_help"), FUEL_TOTAL("fuel_total"),
        CONTAINER_REFRESH("container_refresh"), FUEL_OPEN("fuel_open"), FUEL_BACK("fuel_back"),
        CONTAINER_SETTINGS("container_settings"), CONTAINER_CLOSE("container_close");
        private final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model() { return key(id); }
    }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_generator", "menu/" + id); }
    static NamespacedKey status(String state) {
        if (state == null) return null;
        return switch (state) {
            case "state-working" -> key("status_working"); case "state-no-lava" -> key("status_no_lava");
            case "state-no-water" -> key("status_no_water"); case "state-no-cells" -> key("status_no_cells");
            case "state-unloaded" -> key("status_unloaded"); case "state-carried" -> key("status_carried");
            default -> null;
        };
    }
    static NamespacedKey tier(int tier) { return tier >= 1 && tier <= GeneratorConfig.TIERS ? key("tier_" + tier) : null; }
    static NamespacedKey upgradeTier(int tier) { return key(tier >= GeneratorConfig.TIERS ? "upgrade_tier_max" : "upgrade_tier_available"); }
    static NamespacedKey module(String name, int level) {
        if (!java.util.Set.of("speed", "capacity", "luck").contains(name)) return null;
        return key("module_" + name + (level >= GeneratorStore.MAX_LEVEL ? "_max" : "_available"));
    }
    static NamespacedKey chunk(int level) { return key(level > 0 ? "chunk_owned" : "chunk_available"); }
    static NamespacedKey bunker(long units, long capacity) {
        return key(units <= 0 ? "bunker_empty" : capacity > 0 && units >= capacity ? "bunker_full" : "bunker_partial");
    }
    static Control button(String name) {
        return switch (name) {
            case "menu-rebirth" -> Control.REBIRTH; case "menu-take" -> Control.TAKE; case "menu-close" -> Control.CLOSE;
            case "fuel-help" -> Control.FUEL_HELP; case "container-refresh" -> Control.CONTAINER_REFRESH;
            case "fuel-open" -> Control.FUEL_OPEN; case "fuel-back" -> Control.FUEL_BACK;
            case "container-settings" -> Control.CONTAINER_SETTINGS; case "container-close" -> Control.CONTAINER_CLOSE;
            default -> null;
        };
    }
    static void apply(ItemMeta meta, NamespacedKey key, boolean enabled) {
        if (!enabled || meta == null || key == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(key);
    }
}
