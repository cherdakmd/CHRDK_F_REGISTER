package ru.cherdakmd.chrdkquests;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for freshly constructed virtual menu icons only, never player items. */
final class QuestMenuVisuals {
    enum Control {
        INFO("info"), BACK("back"), CLOSE("close");
        private final String id;
        Control(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_quests", "menu/" + id); }
    }

    static NamespacedKey quest(QuestType type, QuestService.Availability availability) {
        String state = switch (availability) {
            case AVAILABLE -> "available";
            case IN_PROGRESS -> "in_progress";
            case READY -> "ready";
            case DONE -> "done";
            case LOCKED -> "locked";
            case WRONG_FACTION -> "wrong_faction";
            case RANK -> "rank";
        };
        return new NamespacedKey("chrdk_quests", "menu/quest/" + type.id() + "_" + state);
    }

    static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData()) {
            meta.setItemModel(model);
        }
    }

    private QuestMenuVisuals() { }
}
