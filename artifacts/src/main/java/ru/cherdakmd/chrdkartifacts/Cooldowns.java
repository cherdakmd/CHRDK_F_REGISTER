package ru.cherdakmd.chrdkartifacts;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Памятка кулдаунов активных артефактов (ключ — «uuid|артефакт», значение — до какого
 * unix-мс кулдаун). Чистый класс — тестируется без Bukkit.
 *
 * <p>1.4.130: карта переживает перезапуск. {@link ArtifactsPlugin} загружает её из
 * {@link CooldownStore} при включении и сохраняет при выключении/по расписанию;
 * {@link #snapshot()} отдаёт копию для записи, {@link #prune(long)} вычищает
 * истёкшие записи (карта больше не растёт бесконечно), {@link #clearPlayer(UUID)}
 * — точечная уборка.</p>
 */
public final class Cooldowns {

    private final Map<String, Long> until = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public boolean isCoolingDown(UUID playerId, String artifactId, long nowMillis) {
        Long end = until.get(key(playerId, artifactId));
        if (end == null) {
            return false;
        }
        if (end <= nowMillis) {
            // Ленивая уборка: просроченная запись не должна доживать до prune().
            until.remove(key(playerId, artifactId), end);
            return false;
        }
        return true;
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
        dirty = true;
    }

    /** Копия карты для записи на диск. */
    public Map<String, Long> snapshot() {
        return Map.copyOf(until);
    }

    /** Восстанавливает карту из файла: просроченные записи отбрасываются. */
    public void restore(Map<String, Long> saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        saved.forEach((key, end) -> {
            if (key != null && end != null && end > now) {
                until.put(key, end);
            }
        });
        dirty = true;
    }

    /**
     * Убирает просроченные записи.
     *
     * @return сколько записей удалено
     */
    public int prune(long nowMillis) {
        int removed = 0;
        for (Map.Entry<String, Long> entry : new LinkedHashMap<>(until).entrySet()) {
            Long end = entry.getValue();
            if (end == null || end <= nowMillis) {
                if (until.remove(entry.getKey(), end)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    /** Снимает кулдауны игрока (выход/сброс) — по всем артефактам сразу. */
    public int clearPlayer(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        String prefix = playerId + "|";
        int removed = 0;
        for (String existing : new LinkedHashMap<>(until).keySet()) {
            if (existing.startsWith(prefix) && until.remove(existing) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Флаг «изменения записаны на диск». */
    public void markClean() {
        dirty = false;
    }

    public void clear() {
        if (!until.isEmpty()) {
            dirty = true;
        }
        until.clear();
    }

    private static String key(UUID playerId, String artifactId) {
        return playerId + "|" + artifactId;
    }
}
