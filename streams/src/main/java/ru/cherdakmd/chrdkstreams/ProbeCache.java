package ru.cherdakmd.chrdkstreams;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш последнего опроса площадок — чтобы {@code /stream status} не дёргал API
 * заново на каждую команду.
 *
 * <p>Запись считается свежей, пока не истёк TTL. Нулевой TTL выключает кэш:
 * тогда статус всегда спрашивает площадку. Ключ — игрок вместе с площадкой:
 * у стримера их до двух, и ответы у них разные.</p>
 */
final class ProbeCache {

    /** Что запомнили: снимок эфира и когда он получен. */
    record Entry(LiveStream stream, long atMillis) {

        /** Запись ещё актуальна? Нулевой TTL — кэш выключен. */
        boolean fresh(long nowMillis, long ttlMillis) {
            return ttlMillis > 0L && nowMillis - atMillis < ttlMillis;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static String key(UUID uuid, StreamPlatform platform) {
        return uuid + "|" + (platform == null ? "?" : platform.id());
    }

    void put(UUID uuid, StreamPlatform platform, LiveStream stream, long nowMillis) {
        if (uuid == null || platform == null || stream == null) {
            return;
        }
        entries.put(key(uuid, platform), new Entry(stream, nowMillis));
    }

    /** Свежая запись по каналу; пусто — данных нет или они устарели. */
    Optional<Entry> fresh(UUID uuid, StreamPlatform platform, long nowMillis, long ttlMillis) {
        if (uuid == null || platform == null || ttlMillis <= 0L) {
            return Optional.empty();
        }
        Entry entry = entries.get(key(uuid, platform));
        return entry != null && entry.fresh(nowMillis, ttlMillis)
                ? Optional.of(entry) : Optional.empty();
    }

    /** Сумма зрителей по свежим записям игрока — для строки «сейчас смотрят». */
    StreamProbe aggregate(UUID uuid, long nowMillis, long ttlMillis) {
        StreamProbe probe = StreamProbe.OFFLINE;
        if (uuid == null || ttlMillis <= 0L) {
            return probe;
        }
        String prefix = uuid + "|";
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().fresh(nowMillis, ttlMillis)) {
                probe = probe.plus(entry.getValue().stream());
            }
        }
        return probe;
    }

    void forget(UUID uuid) {
        if (uuid == null) {
            return;
        }
        String prefix = uuid + "|";
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
