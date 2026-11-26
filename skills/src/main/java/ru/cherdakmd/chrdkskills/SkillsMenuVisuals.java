package ru.cherdakmd.chrdkskills;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models only for virtual GUI navigation/state items, not inventory or crystal items. */
final class SkillsMenuVisuals {
    enum Control {
        GUIDE("guide"), BACK("back"), CLOSE("close"), STEPS("steps"), SUMMARY("summary"),
        PERK_ACTIVE("perk_active"), PERK_LOCKED("perk_locked"),
        RESET_ATTRIBUTES("reset_attributes"), CONFIRM_ATTRIBUTES("confirm_attributes"),
        RESET_BUILDS("reset_builds"), CONFIRM_BUILDS("confirm_builds");
        private final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_skills", "menu/" + id); }
    }

    static NamespacedKey tab(SkillsMenuApi.Tab tab, boolean selected) {
        String id = switch (tab) {
            case SKILLS -> "skills";
            case ATTRIBUTES -> "attributes";
            case TOP -> "top";
            case REBIRTH -> "rebirth";
            case POTIONS -> "potions";
            case BUILDS -> "builds";
        };
        return new NamespacedKey("chrdk_skills", "menu/tab/" + id + (selected ? "_selected" : ""));
    }

    static NamespacedKey skill(SkillType skill) {
        return new NamespacedKey("chrdk_skills", "menu/skill/" + skill.id());
    }
    static NamespacedKey rebirth(SkillType skill, boolean ready) {
        return new NamespacedKey("chrdk_skills", "menu/rebirth/" + skill.id() + (ready ? "_ready" : "_waiting"));
    }
    static NamespacedKey attribute(PlayerAttribute attribute) {
        return new NamespacedKey("chrdk_skills", "menu/attribute/" + attribute.id());
    }
    static NamespacedKey branch(BuildBranch branch) {
        return new NamespacedKey("chrdk_skills", "menu/branch/" + branch.id());
    }

    static Control button(String key) {
        return switch (key) {
            case "menu-guide-button" -> Control.GUIDE;
            case "menu-close" -> Control.CLOSE;
            case "detail-back", "menu-rebirth-back" -> Control.BACK;
            case "menu-steps-title" -> Control.STEPS;
            default -> null;
        };
    }

    static Control perk(boolean active) { return active ? Control.PERK_ACTIVE : Control.PERK_LOCKED; }
    static Control reset(boolean builds, boolean armed) {
        return builds ? (armed ? Control.CONFIRM_BUILDS : Control.RESET_BUILDS)
                : (armed ? Control.CONFIRM_ATTRIBUTES : Control.RESET_ATTRIBUTES);
    }

    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(model);
        }
    }
    private SkillsMenuVisuals() { }
}
