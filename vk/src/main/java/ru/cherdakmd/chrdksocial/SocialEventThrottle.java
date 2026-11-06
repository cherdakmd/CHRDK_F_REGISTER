package ru.cherdakmd.chrdksocial;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Анти-спам для событий сообщества: один и тот же тип события от одного игрока
 * не уходит в беседу чаще заданного интервала. Серверные события (старт/стоп)
 * дедуплицируются отдельно, без привязки к игроку.
 *
 * <p>Часы подставляются снаружи, чтобы правило покрывалось обычным JVM-тестом
 * без ожидания реального времени.</p>
 */
public final class SocialEventThrottle {

    private static final int MAX_KEYS = 4096;

    private final LongSupplier clock;
    private final Map<String, Long> last = new HashMap<>();

    public SocialEventThrottle(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public synchronized boolean allow(String kind, String player, long cooldownMillis) {
        long now = clock.getAsLong();
        String key = kind + ":" + (player == null ? "" : player);
        Long previous = last.get(key);
        if (cooldownMillis > 0L && previous != null && now - previous < cooldownMillis) {
            return false;
        }
        last.put(key, now);
        if (last.size() > MAX_KEYS) {
            long horizon = Math.max(cooldownMillis, 3_600_000L);
            last.entrySet().removeIf(entry -> now - entry.getValue() > horizon);
        }
        return true;
    }

    public synchronized void clear() {
        last.clear();
    }
}
