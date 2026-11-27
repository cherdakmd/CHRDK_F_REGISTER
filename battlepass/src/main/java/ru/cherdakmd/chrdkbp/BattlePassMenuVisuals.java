package ru.cherdakmd.chrdkbp;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models only for newly constructed virtual mission-menu entries. */
final class BattlePassMenuVisuals {
    private BattlePassMenuVisuals() { }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_battlepass", "menu/" + id); }
    static NamespacedKey mission(BattlePassConfig.MissionDef mission, boolean unlocked, BattlePassView.State state) {
        if (!unlocked) return key("mission_future");
        // Explicit owner-selected icons must retain their intended appearance.
        if (mission == null || mission.type() == null || state == null || !mission.icon().isEmpty()) return null;
        return key("mission_" + mission.type().name().toLowerCase(Locale.ROOT) + "_" + state.name().toLowerCase(Locale.ROOT));
    }
    static NamespacedKey filter(BattlePassFilter filter) {
        return filter == null ? null : key("filter_" + filter.name().toLowerCase(Locale.ROOT));
    }
    static NamespacedKey button(String name) {
        return switch (name) {
            case "menu-filter-empty" -> key("empty");
            case "menu-prev" -> key("prev");
            case "menu-next" -> key("next");
            case "menu-claim-all" -> key("claim_all");
            case "menu-back" -> key("back");
            case "menu-refresh" -> key("refresh");
            case "menu-premium-name" -> key("premium");
            case "menu-close" -> key("close");
            default -> null;
        };
    }
    static NamespacedKey summary() { return key("summary"); }
    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (!enabled || meta == null || model == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(model);
    }
}
