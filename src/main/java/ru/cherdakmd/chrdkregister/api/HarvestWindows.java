package ru.cherdakmd.chrdkregister.api;

import java.util.*;

/** Общие identity-окна для одного harvest-event: два модуля получают одну заявку, не два таймера. */
public final class HarvestWindows<K, E, V> {
    public static final int LIMIT = 4096;
    private record Ticket<E, V>(E event, V value) { }
    public record Reservation<V>(V value, boolean fresh) { }
    private final Map<K, Ticket<E, V>> entries = new HashMap<>();
    public Reservation<V> reserve(K key, E event, V value) {
        if (key == null || event == null || value == null) return null;
        var previous = entries.get(key);
        if (previous != null) return previous.event() == event ? new Reservation<>(previous.value(), false) : null;
        if (entries.size() >= LIMIT) return null;
        entries.put(key, new Ticket<>(event, value)); return new Reservation<>(value, true);
    }
    public V get(K key) { var entry = entries.get(key); return entry == null ? null : entry.value(); }
    public boolean remove(K key, V value) {
        var entry = entries.get(key);
        return entry != null && entry.value() == value && entries.remove(key, entry);
    }
    public List<Map.Entry<K, V>> snapshot() { return entries.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue().value())).toList(); }
    public int size() { return entries.size(); }
}
