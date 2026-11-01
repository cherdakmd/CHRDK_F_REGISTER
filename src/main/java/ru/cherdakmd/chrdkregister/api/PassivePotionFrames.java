package ru.cherdakmd.chrdkregister.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

/** Полный снимок источника заменяется атомарно; исчезновение одного не гасит другие. */
final class PassivePotionFrames<P, S, E, V> {
    private record Frame<E, V>(long expires, Map<E, V> effects) { }
    private final Map<P, Map<S, Frame<E, V>>> players = new HashMap<>();

    void replace(P player, S source, Map<E, V> effects, long expires) {
        if (effects.isEmpty()) {
            var sources = players.get(player);
            if (sources != null) {
                sources.remove(source);
                if (sources.isEmpty()) players.remove(player);
            }
        } else {
            players.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                    .put(source, new Frame<>(expires, Map.copyOf(effects)));
        }
    }

    Map<E, V> desired(P player, long now, Predicate<S> enabled, BinaryOperator<V> merge) {
        Map<E, V> result = new LinkedHashMap<>();
        var sources = players.get(player);
        if (sources == null) return result;
        sources.entrySet().removeIf(entry -> entry.getValue().expires() <= now || !enabled.test(entry.getKey()));
        for (var frame : sources.values()) frame.effects().forEach((effect, value) -> result.merge(effect, value, merge));
        if (sources.isEmpty()) players.remove(player);
        return result;
    }

    java.util.Set<P> players() { return java.util.Set.copyOf(players.keySet()); }
    void remove(P player) { players.remove(player); }
    void removeSources(Predicate<S> predicate) {
        players.values().forEach(sources -> sources.keySet().removeIf(predicate));
        players.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
    void clear() { players.clear(); }
}
