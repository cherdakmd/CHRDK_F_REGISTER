package ru.cherdakmd.chrdkskills;

import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for the three exact built-in rebirth rewards, never arbitrary owner-defined IDs. */
final class SkillRewardVisuals {
    private static final Map<String, SkillsConfig.UniqueItem> STOCK = Map.of(
        "heart_of_the_sea", new SkillsConfig.UniqueItem("heart_of_the_sea", "HEART_OF_THE_SEA", "Сердце перерождения", List.of("Талисман тех, кто начинал заново.")),
        "nether_star", new SkillsConfig.UniqueItem("nether_star", "NETHER_STAR", "Звезда возрождения", List.of("Свет на месте сгоревшей жизни.")),
        "dragon_egg", new SkillsConfig.UniqueItem("dragon_egg", "DRAGON_EGG", "Яйцо дракона перерождений", List.of("Из него вылупится то, чем ты станешь."))
    );
    static NamespacedKey model(SkillsConfig.UniqueItem item) {
        if (item == null || item.id() == null || !item.equals(STOCK.get(item.id()))) return null;
        return new NamespacedKey("chrdk_skills", "rebirth/" + item.id());
    }
    static void applyNew(ItemMeta meta, SkillsConfig.UniqueItem item, boolean enabled) {
        if (!enabled || meta == null) return;
        var key = model(item);
        if (key != null && meta.getItemModel() == null && !meta.hasCustomModelData()) meta.setItemModel(key);
    }
    private SkillRewardVisuals() { }
}
