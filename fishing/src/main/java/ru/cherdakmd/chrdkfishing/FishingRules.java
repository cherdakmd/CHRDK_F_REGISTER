package ru.cherdakmd.chrdkfishing;

import java.util.Map;
import java.util.Set;

/** Immutable domain options; storage and a Bukkit location resolver are separate. */
public record FishingRules(boolean locationEnabled, boolean environmentEnabled,
                           Map<String, Set<String>> zones, Map<String, Set<String>> biomes) {
    public FishingRules {
        zones = immutable(zones);
        biomes = immutable(biomes);
    }
    private static Map<String, Set<String>> immutable(Map<String, Set<String>> map) {
        if (map == null) throw new IllegalArgumentException("missing area rules");
        var copy = new java.util.LinkedHashMap<String, Set<String>>();
        for (var entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty())
                throw new IllegalArgumentException("empty area rule");
            Set<String> ids = Set.copyOf(entry.getValue());
            for (String id : ids) if (FishCatalog.byId(id) == null) throw new IllegalArgumentException("unknown fish: " + id);
            copy.put(entry.getKey(), ids);
        }
        return Map.copyOf(copy);
    }
    public static FishingRules defaults() { return new FishingRules(false, true, Map.of(), Map.of()); }
    /** Zone overrides biome; missing configuration falls back to the global pool. */
    public Set<String> eligible(String zone, String biome) {
        if (!locationEnabled) return Set.of();
        if (zone != null && zones.containsKey(zone)) return zones.get(zone);
        if (biome != null && biomes.containsKey(biome)) return biomes.get(biome);
        return Set.of();
    }
    public record Context(String zone, String biome, boolean night, boolean raining) { }
}
