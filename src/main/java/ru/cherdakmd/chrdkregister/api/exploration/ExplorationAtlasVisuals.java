package ru.cherdakmd.chrdkregister.api.exploration;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Fixed models for virtual atlas entries only; no player items or progress mutations. */
final class ExplorationAtlasVisuals {
    enum Icon {
        UNKNOWN("unknown"), VISITED("visited"), COMPLETE("complete"), SUMMARY("summary"),
        TASK_PROGRESS("task_progress"), TASK_DONE("task_done"), STUDY("study"), PREV("prev"), NEXT("next"), BACK("back"), CLOSE("close");
        private final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_core", "menu/atlas/" + id); }
    }
    private ExplorationAtlasVisuals() { }
    static Icon biome(boolean visited,int done) { return done == 5 ? Icon.COMPLETE : visited ? Icon.VISITED : Icon.UNKNOWN; }
    static Icon task(long progress,long target) { return progress >= target ? Icon.TASK_DONE : Icon.TASK_PROGRESS; }
    static void apply(ItemMeta meta,Icon icon,boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData()) meta.setItemModel(icon.model());
    }
}
