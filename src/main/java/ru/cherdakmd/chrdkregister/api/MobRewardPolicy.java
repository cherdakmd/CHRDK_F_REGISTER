package ru.cherdakmd.chrdkregister.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/** API38: временные участники нашествия не являются источником добычи/прогрессии.
 * Проверка не зависит от того, включён ли Hunt в момент обработки смерти. */
public final class MobRewardPolicy {
    public static final NamespacedKey TEMPORARY = new NamespacedKey("chrdk_hunt", "full_moon");
    public static boolean temporary(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(TEMPORARY);
    }
    /** Provenance Classes — не уровень/статы. Остальные чужие PDC не принимаем за vanilla-моба. */
    public static boolean onlyNaturalProvenance(Entity entity) {
        var natural = new NamespacedKey("chrdk_classes", "natural-v1");
        return entity.getPersistentDataContainer().getKeys().stream().allMatch(natural::equals);
    }
    private MobRewardPolicy() { }
}
