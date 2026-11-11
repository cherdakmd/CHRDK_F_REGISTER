package ru.cherdakmd.chrdkregister.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/** API38: временные участники нашествия не являются источником добычи/прогрессии.
 * Проверка не зависит от того, включён ли снятого источника в момент обработки смерти. */
public final class MobRewardPolicy {
    public static final NamespacedKey TEMPORARY = new NamespacedKey("chrdk_hunt", "full_moon");
    private static final NamespacedKey WILD = new NamespacedKey("chrdk_hunt", "wild_variant");
    private static final NamespacedKey PROVENANCE = new NamespacedKey("chrdk_classes", "natural-v1");
    public static boolean temporary(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(TEMPORARY);
    }
    /** Биомная разновидность — не босс, даже если у неё есть читаемое имя. */
    private MobRewardPolicy() { }
}
