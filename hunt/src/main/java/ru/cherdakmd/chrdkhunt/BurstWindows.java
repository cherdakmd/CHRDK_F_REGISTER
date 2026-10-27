package ru.cherdakmd.chrdkhunt;

import java.util.*;
import java.util.function.Predicate;

/** До 128 временных эффектов: без вытеснения/продления, снятие возвращает ровно свой эффект. */
final class BurstWindows<T> {
    static final int LIMIT = 128;
    record Entry<T>(UUID id, UUID owner, T value, long until) { }
    private final Map<UUID, Entry<T>> active = new HashMap<>();
    Entry<T> get(UUID id) { return active.get(id); }
    boolean contains(UUID id) { return active.containsKey(id); }
    boolean start(UUID id, UUID owner, T value, long tick, int duration) {
        if (id == null || owner == null || value == null || tick < 0 || duration <= 0
                || tick > Long.MAX_VALUE - duration || active.containsKey(id) || active.size() >= LIMIT) return false;
        active.put(id, new Entry<>(id, owner, value, tick + duration));
        return true;
    }
    Entry<T> remove(UUID id) { return active.remove(id); }
    List<Entry<T>> drain(Predicate<Entry<T>> predicate) {
        List<Entry<T>> removed = new ArrayList<>();
        active.entrySet().removeIf(entry -> {
            if (!predicate.test(entry.getValue())) return false;
            removed.add(entry.getValue()); return true;
        });
        return removed;
    }
    List<Entry<T>> snapshot() { return List.copyOf(active.values()); }
    List<Entry<T>> clear() { return drain(entry -> true); }
    int size() { return active.size(); }
}
