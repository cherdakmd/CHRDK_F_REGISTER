package ru.cherdakmd.chrdkenchants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Захват события, не вечная отметка моба. Отменённое событие можно заменить даже в тот же тик. */
final class DeathBookClaims<T> {
    private final Map<UUID, T> pending = new HashMap<>();
    private final Predicate<T> cancelled;
    DeathBookClaims(Predicate<T> cancelled) { this.cancelled = cancelled; }
    boolean begin(UUID id, T event) {
        T old = pending.get(id);
        if (old != null && !cancelled.test(old)) return false;
        if (old == null && pending.size() >= 4096) return false;
        pending.put(id, event); return true;
    }
    boolean finish(UUID id, T event) {
        if (pending.get(id) != event) return false;
        pending.remove(id);
        return !cancelled.test(event);
    }
    void discard(UUID id, T event) { if (pending.get(id) == event) pending.remove(id); }
    void clear() { pending.clear(); }
    int size() { return pending.size(); }
}
