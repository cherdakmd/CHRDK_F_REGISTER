package ru.cherdakmd.chrdkqueue;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Настройки очереди из config.yml (queue:*). */
public record QueueConfig(
        boolean enabled,
        int regularLimit,
        Set<String> vipGroups,
        String vipPermission,
        boolean useVipPermission,
        int reservationSeconds,
        int maxQueue,
        int expireMinutes,
        boolean debugLog,
        boolean rankPriority) {

    public static QueueConfig from(FileConfiguration config) {
        Set<String> groups = new LinkedHashSet<>();
        for (String group : config.getStringList("queue.vip-groups")) {
            if (group == null || group.isBlank()) {
                continue;
            }
            groups.add(group.trim().toLowerCase(Locale.ROOT));
        }
        return new QueueConfig(
                config.getBoolean("queue.enabled", true),
                Math.max(1, config.getInt("queue.regular-limit", 10)),
                groups,
                config.getString("queue.vip-permission", "chrdkqueue.vip").trim(),
                config.getBoolean("queue.use-vip-permission", false),
                Math.max(5, config.getInt("queue.reservation-seconds", 30)),
                Math.max(1, config.getInt("queue.max-queue", 100)),
                Math.max(1, config.getInt("queue.expire-minutes", 15)),
                config.getBoolean("queue.debug-log", false),
                config.getBoolean("queue.rank-priority", true));
    }
}
