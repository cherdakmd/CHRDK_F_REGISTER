package ru.cherdakmd.chrdkartifacts;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Памятка кулдаунов активных артефактов (ключ — «ник|артефакт», значение — до какого
 * unix-мс кулдаун). Чистый класс — тестируется без Bukkit.
 */
public final class Cooldowns {

    private final Map<String, Long> until = new ConcurrentHashMap<>();

    public boolean isCoolingDown(UUID playerId, String artifactId, long nowMillis) {
        Long end = until.get(key(playerId, artifactId));
        return end != null && end > nowMillis;
    }

    /** Остаток кулдауна в секундах (округление вверх), 0 если кулдауна нет. */
    public int remainingSeconds(UUID playerId, String artifactId, long nowMillis) {
        Long end = until.get(key(playerId, artifactId));
        if (end == null || end <= nowMillis) {
            return 0;
        }
        return (int) Math.ceil((end - nowMillis) / 1000.0);
    }

    public void start(UUID playerId, String artifactId, long nowMillis, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return;
        }
        until.put(key(playerId, artifactId), nowMillis + Duration.ofSeconds(cooldownSeconds).toMillis());
    }

    public void clear() {
        until.clear();
    }

    private static String key(UUID playerId, String artifactId) {
        return playerId + "|" + artifactId;
    }
}
