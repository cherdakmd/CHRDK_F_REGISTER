package ru.cherdakmd.diamondshop;

import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;

/** Exact models shipped in the common pack, applied only to a newly built shop template. */
final class ShopBookVisuals {
    private static final Set<String> CUSTOM = Set.of(
            "air_reserve", "autosmelt", "berserk", "bleeding", "briar_guard",
            "brittle", "bulwark", "careful_work", "chainspark", "clumsy_swing",
            "crippling_edge", "cursed_blood", "duelist", "dull_blade", "dull_pick",
            "excavator", "execution", "explosive", "exposed", "faltering_guard",
            "featherstep", "fire_prone", "fortified", "fortunate", "fragile_bones",
            "frenzy", "froggy", "frost_resistance", "frostarrow", "garden_step",
            "gluttony", "grounded", "harvest_replant", "headhunter", "heavy_hull",
            "iceward", "jinxed", "keen_edge", "laststand", "lavawalker",
            "lifesteal", "lightfoot", "lightningrod", "midas", "paralyze",
            "phoenix", "pinning_shot", "plunder", "prospector", "provoking_strike",
            "pve_anchor", "quickdraw", "quiet_step", "retribution", "sapping_edge",
            "sapping_shot", "scholar", "second_skin", "seer", "signal_guard",
            "slayer", "slow_learner", "sluggish_shot", "sniper", "souleater",
            "steady_aim", "stormarrow", "swiftfeet", "thin_padding", "thunderstrike",
            "tool_safeguard", "tracer", "truesight", "veinminer", "vitality",
            "weak_draw");
    private static final Set<String> VANILLA = Set.of(
            "aqua_affinity", "bane_of_arthropods", "binding_curse", "blast_protection", "breach",
            "channeling", "density", "depth_strider", "efficiency", "feather_falling",
            "fire_aspect", "fire_protection", "flame", "fortune", "frost_walker",
            "impaling", "infinity", "knockback", "looting", "loyalty",
            "luck_of_the_sea", "lunge", "lure", "mending", "multishot",
            "piercing", "power", "projectile_protection", "protection", "punch",
            "quick_charge", "respiration", "riptide", "sharpness", "silk_touch",
            "smite", "soul_speed", "sweeping_edge", "swift_sneak", "thorns",
            "unbreaking", "vanishing_curse", "wind_burst");
    static NamespacedKey model(ShopBookKey.Spec spec) {
        if (spec == null || !(spec.custom() ? CUSTOM : VANILLA).contains(spec.id())) return null;
        return new NamespacedKey("chrdk_enchants", "book/" + (spec.custom() ? "custom/" : "vanilla/") + spec.id());
    }
    static boolean applyNew(ItemMeta meta, ShopBookKey.Spec spec, boolean enabled) {
        if (!enabled || meta == null) return false;
        var key = model(spec);
        if (key == null || meta.getItemModel() != null || meta.hasCustomModelData()) return false;
        meta.setItemModel(key);
        return true;
    }
    private ShopBookVisuals() { }
}
