package ru.cherdakmd.chrdkenchants;

import java.util.*;

/** Одноразовое подтверждение, ограниченное по времени/памяти, без выдачи результата preview. */
final class Confirmations<T> {
    static final int LIMIT = 256;
    record Quote<T>(UUID code, long created, long until, T plan) { }
    private final Map<UUID, Quote<T>> entries = new HashMap<>();
    Quote<T> put(UUID player, long now, T plan) {
        entries.entrySet().removeIf(e -> now < e.getValue().created() || now > e.getValue().until());
        if (player == null || plan == null || now < 0 || now > Long.MAX_VALUE - 30000
                || !entries.containsKey(player) && entries.size() >= LIMIT) return null;
        var quote = new Quote<>(UUID.randomUUID(), now, now + 30000, plan); entries.put(player, quote); return quote;
    }
    T take(UUID player, UUID code, long now) {
        var quote = entries.get(player);
        if (quote == null || !quote.code().equals(code)) return null;
        entries.remove(player); // Даже устаревшее/непригодное подтверждение не воспроизводится повторно.
        return now >= quote.created() && now <= quote.until() ? quote.plan() : null;
    }
    void remove(UUID player) { entries.remove(player); }
    void clear() { entries.clear(); }
    int size() { return entries.size(); }
}
