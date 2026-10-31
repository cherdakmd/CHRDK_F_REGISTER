package ru.cherdakmd.chrdktierforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Короткоживущие снимки инструмента: ключ не пересекает игроков/миры/блоки. */
final class ForgeBreakCache<T> {
    record Key(UUID player, UUID world, int x, int y, int z) { }

    private final int capacity;
    private final Map<Key, T> entries = new LinkedHashMap<>();

    ForgeBreakCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Ёмкость должна быть положительной");
        this.capacity = capacity;
    }

    void put(Key key, T value) {
        entries.remove(key);
        if (entries.size() >= capacity) entries.remove(entries.keySet().iterator().next());
        entries.put(key, value);
    }

    T get(Key key) {
        return entries.get(key);
    }

    void clear() {
        entries.clear();
    }
}
