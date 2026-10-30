package ru.cherdakmd.chrdkregister.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Краткое согласие с котировкой, НЕ финансовый резерв и НЕ банковская квитанция. */
public final class EconomyConfirmations {
    public static final long TTL_NANOS = 30_000_000_000L;
    public static final int MAX_ENTRIES = 4096;
    private record Entry(Object session, Object quote, long created) { }
    private final Map<UUID, Entry> entries = new HashMap<>();
    private long swept;
    private void sweep(long now) {
        if (now - swept >= 1_000_000_000L || entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(e -> now - e.created < 0 || now - e.created >= TTL_NANOS);
            swept = now;
        }
    }
    public synchronized boolean offer(UUID owner, Object session, Object quote, long now) {
        Objects.requireNonNull(owner); Objects.requireNonNull(session); Objects.requireNonNull(quote);
        sweep(now);
        if (!entries.containsKey(owner) && entries.size() >= MAX_ENTRIES) return false;
        entries.put(owner, new Entry(session, quote, now)); return true;
    }
    /** Любая попытка подтверждения одноразова; смена соединения или условий её отклоняет. */
    public synchronized boolean consume(UUID owner, Object session, Object quote, long now) {
        Entry e = entries.remove(owner); sweep(now);
        return e != null && e.session == session && e.quote.equals(quote)
                && now - e.created >= 0 && now - e.created < TTL_NANOS;
    }
    public synchronized void clear() { entries.clear(); }
    public synchronized int size() { return entries.size(); }
}
