package ru.cherdakmd.chrdkregister.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/** API38: временные участники нашествия не являются источником добычи/прогрессии.
 * Проверка не зависит от того, включён ли Hunt в момент обработки смерти. */
public final class MobRewardPolicy {
    public static final NamespacedKey TEMPORARY = new NamespacedKey("chrdk_hunt", "full_moon");
    private static final NamespacedKey WILD = new NamespacedKey("chrdk_hunt", "wild_variant");
    private static final NamespacedKey PROVENANCE = new NamespacedKey("chrdk_classes", "natural-v1");
    public static boolean temporary(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(TEMPORARY);
    }
    /** Биомная разновидность — не босс, даже если у неё есть читаемое имя. */
    public static boolean wild(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(WILD);
    }
    /** Provenance Classes — не уровень/статы. Остальные чужие PDC не принимаем за vanilla-моба. */
    public static boolean onlyNaturalProvenance(Entity entity) {
        return entity.getPersistentDataContainer().getKeys().stream().allMatch(PROVENANCE::equals);
    }
    private MobRewardPolicy() { }
}
