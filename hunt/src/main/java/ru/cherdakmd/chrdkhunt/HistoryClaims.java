package ru.cherdakmd.chrdkhunt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Одно событие смерти на сущность; поздняя отмена допускает замену в том же тике. */
final class HistoryClaims<T> {
    static final int LIMIT = 4096;
    private record Claim<T>(UUID owner, T event) { }
    private final Map<UUID, Claim<T>> pending = new HashMap<>();
    private final Predicate<T> cancelled;
    HistoryClaims(Predicate<T> cancelled) { this.cancelled = cancelled; }
    boolean begin(UUID victim, UUID owner, T event) {
        var old = pending.get(victim);
        if (old != null && !cancelled.test(old.event)) return false;
        if (old == null && pending.size() >= LIMIT) return false;
        pending.put(victim, new Claim<>(owner, event));
        return true;
    }
    boolean finish(UUID victim, T event) {
        var claim = pending.get(victim);
        if (claim == null || claim.event != event) return false;
        pending.remove(victim);
        return !cancelled.test(event);
    }
    void discard(UUID victim, T event) {
        var claim = pending.get(victim);
        if (claim != null && claim.event == event) pending.remove(victim);
    }
    List<UUID> forget(UUID owner) {
        List<UUID> victims = pending.entrySet().stream().filter(e -> e.getValue().owner.equals(owner)).map(Map.Entry::getKey).toList();
        victims.forEach(pending::remove);
        return victims;
    }
    void clear() { pending.clear(); }
    int size() { return pending.size(); }
}
