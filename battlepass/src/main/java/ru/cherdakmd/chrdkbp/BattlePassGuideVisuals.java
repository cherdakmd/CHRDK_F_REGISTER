package ru.cherdakmd.chrdkbp;

import java.util.Locale;
import java.util.Set;
import org.bukkit.NamespacedKey;

/** Fixed models for virtual guide controls and reward-track previews. */
final class BattlePassGuideVisuals {
    private static final Set<String> TRACKS = Set.of("FREE", "VIP", "PREMIUM");
    private static final Set<String> STATES = Set.of("no-reward", "received", "future", "access-locked", "in-progress", "ready");
    private BattlePassGuideVisuals() { }
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_battlepass", "menu/guide/" + name); }
    static NamespacedKey button(String title) {
        if (title == null) return null;
        return switch (title) {
            case "guide-season" -> key("season"); case "guide-tasks" -> key("tasks");
            case "guide-rewards" -> key("rewards"); case "guide-help" -> key("help");
            case "menu-claim-all" -> key("claim_all"); case "menu-premium-name" -> key("premium");
            case "atlas-title-season" -> key("atlas"); case "exp-menu" -> key("expeditions");
            case "guide-bank" -> key("bank"); case "guide-recommended" -> key("recommended");
            case "menu-close" -> key("close"); case "guide-reward-page" -> key("reward_page");
            case "guide-free" -> key("free"); case "guide-vip" -> key("vip");
            case "guide-premium" -> key("premium_label"); case "guide-mission-name" -> key("mission");
            case "guide-back" -> key("back"); case "menu-prev" -> key("prev");
            case "menu-next" -> key("next"); case "guide-claim-one" -> key("claim_one");
            case "guide-fair" -> key("fair"); case "guide-targets" -> key("targets");
            default -> null;
        };
    }
    static NamespacedKey track(String track, String state) {
        if (track == null || state == null || !TRACKS.contains(track) || !state.startsWith("guide-")) return null;
        String suffix = state.substring("guide-".length());
        if (!STATES.contains(suffix)) return null;
        return key("track_" + track.toLowerCase(Locale.ROOT) + "_" + suffix.replace('-', '_'));
    }
}
