package ru.cherdakmd.chrdkcosmetics;

import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models only freshly created virtual menu icons; no particles, permissions or player items. */
final class CosmeticsMenuVisuals {
    enum Control {
        SUMMARY("summary"), TRAILS("trails"), JOIN("join"), COLORS("colors"), LABELS("labels"), SLOTS("slots"),
        PRESETS("presets"), HAT("hat"), PETS("pets"), MENU("menu"), CLOSE("close"), EMPTY("empty"),
        PREV("prev"), NEXT("next"), BACK("back"), CLEAR("clear"), TRAILS_INFO("trails_info");
        private final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_cosmetics", "menu/" + id); }
    }

    // A server may redefine or repurpose a stock ID. Only the exact stock definition
    // gets its themed art; every modified/custom definition keeps its existing icon.
    private static final Map<String, CosmeticsDefs.TrailDef> STOCK = Map.of(
        "flame", new CosmeticsDefs.TrailDef("flame", "<red>Огненный шлейф</red>", "flame", 3, .15, 0, "trail-flame", 1),
        "hearts", new CosmeticsDefs.TrailDef("hearts", "<light_purple>Сердечки</light_purple>", "heart", 1, .25, 0, "trail-hearts", 2),
        "notes", new CosmeticsDefs.TrailDef("notes", "<aqua>Ноты</aqua>", "note", 2, .3, 1, "trail-notes", 2),
        "sparkles", new CosmeticsDefs.TrailDef("sparkles", "<yellow>Искры Энда</yellow>", "end_rod", 2, .2, 0, "trail-sparkles", 3),
        "magic", new CosmeticsDefs.TrailDef("magic", "<dark_purple>Ведьмина магия</dark_purple>", "witch", 4, .25, 0, "trail-magic", 3),
        "gold", new CosmeticsDefs.TrailDef("gold", "<gold>Золотая пыль</gold>", "electric_spark", 3, .2, 0, "", 4),
        "aurora", new CosmeticsDefs.TrailDef("aurora", "<gradient:#b388ff:#88ffff>Сияние</gradient>", "firework", 2, .25, 0, "", 5));

    static NamespacedKey trail(CosmeticsDefs.TrailDef def, boolean open, boolean chosen, boolean valid) {
        if (def == null || !def.equals(STOCK.get(def.id()))) return null;
        String state = !valid ? "unavailable" : !open ? (chosen ? "suspended" : "locked") : chosen ? "selected" : "available";
        return new NamespacedKey("chrdk_cosmetics", "menu/trail/" + def.id() + "/" + state);
    }
    static Control button(String key) {
        return switch (key) {
            case "gui-summary" -> Control.SUMMARY;
            case "gui-trails" -> Control.TRAILS;
            case "gui-join" -> Control.JOIN;
            case "gui-colors" -> Control.COLORS;
            case "gui-labels" -> Control.LABELS;
            case "gui-slots" -> Control.SLOTS;
            case "gui-presets" -> Control.PRESETS;
            case "gui-hat" -> Control.HAT;
            case "gui-pets" -> Control.PETS;
            case "gui-menu" -> Control.MENU;
            case "gui-close" -> Control.CLOSE;
            case "gui-empty" -> Control.EMPTY;
            case "gui-prev" -> Control.PREV;
            case "gui-next" -> Control.NEXT;
            case "gui-back" -> Control.BACK;
            case "gui-clear" -> Control.CLEAR;
            case "gui-trails-info" -> Control.TRAILS_INFO;
            default -> null;
        };
    }
    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(model);
        }
    }
    private CosmeticsMenuVisuals() { }
}
