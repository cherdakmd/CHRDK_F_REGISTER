package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.player.PlayerFishEvent;
import ru.cherdakmd.chrdkfishing.FishingRules;

/** Startup snapshot; invalid configuration prevents the prototype from starting. */
final class FishingSettings {
    private record Zone(String id, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        boolean contains(Location p) {
            return p.getWorld() != null && p.getWorld().getName().equals(world)
                    && p.getBlockX() >= minX && p.getBlockX() <= maxX
                    && p.getBlockY() >= minY && p.getBlockY() <= maxY
                    && p.getBlockZ() >= minZ && p.getBlockZ() <= maxZ;
        }
    }
    private final int chance;
    private final FishingRules rules;
    private final List<Zone> zones;
    private FishingSettings(int chance, FishingRules rules, List<Zone> zones) {
        this.chance = chance; this.rules = rules; this.zones = List.copyOf(zones);
    }
    int chance() { return chance; }
    FishingRules rules() { return rules; }
    static FishingSettings load(FileConfiguration config) {
        if (!config.isInt("custom-catch-permyriad")) throw new IllegalArgumentException("custom-catch-permyriad must be an integer");
        int chance = config.getInt("custom-catch-permyriad");
        if (chance < 0 || chance > 10000) throw new IllegalArgumentException("custom-catch-permyriad outside 0..10000");
        if (!config.isBoolean("location.enabled") || !config.isBoolean("environment.enabled"))
            throw new IllegalArgumentException("location.enabled and environment.enabled must be booleans");
        Map<String, Set<String>> biomePools = new LinkedHashMap<>(), zonePools = new LinkedHashMap<>();
        List<Zone> zones = new ArrayList<>();
        ConfigurationSection biomes = config.getConfigurationSection("location.biomes");
        if (biomes != null) for (String biome : biomes.getKeys(false)) {
            String key = biome.toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid biome: " + biome);
            if (biomePools.put(key, pool(biomes, biome)) != null) throw new IllegalArgumentException("duplicate biome: " + biome);
        }
        ConfigurationSection sections = config.getConfigurationSection("location.zones");
        if (sections != null) for (String id : sections.getKeys(false).stream().sorted().toList()) {
            ConfigurationSection z = sections.getConfigurationSection(id);
            if (z == null || !z.isString("world") || z.getString("world").isBlank()) throw new IllegalArgumentException("invalid zone " + id);
            for (String field : List.of("min-x", "max-x", "min-y", "max-y", "min-z", "max-z"))
                if (!z.isInt(field)) throw new IllegalArgumentException("invalid zone coordinate " + id + "." + field);
            int minX=z.getInt("min-x"), maxX=z.getInt("max-x"), minY=z.getInt("min-y"), maxY=z.getInt("max-y"), minZ=z.getInt("min-z"), maxZ=z.getInt("max-z");
            if (minX>maxX || minY>maxY || minZ>maxZ) throw new IllegalArgumentException("inverted zone bounds " + id);
            zones.add(new Zone(id, z.getString("world"), minX, maxX, minY, maxY, minZ, maxZ));
            zonePools.put(id, pool(z, "fish"));
        }
        return new FishingSettings(chance, new FishingRules(config.getBoolean("location.enabled"),
                config.getBoolean("environment.enabled"), zonePools, biomePools), zones);
    }
    private static Set<String> pool(ConfigurationSection section, String path) {
        if (!section.isList(path)) throw new IllegalArgumentException("fish list required: " + section.getCurrentPath() + "." + path);
        var values = section.getList(path);
        if (values == null || values.isEmpty() || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("invalid fish list: " + path);
        var out = new java.util.LinkedHashSet<String>();
        for (Object value : values) if (!out.add((String) value)) throw new IllegalArgumentException("duplicate fish: " + value);
        return Set.copyOf(out);
    }
    FishingRules.Context context(PlayerFishEvent event) {
        var hook = event.getHook();
        var world = hook.getWorld();
        long time = world.getTime();
        String zone = null, biome = null;
        if (rules.locationEnabled()) {
            Location location = hook.getLocation();
            for (Zone candidate : zones) if (candidate.contains(location)) { zone = candidate.id(); break; }
            biome = location.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
        }
        return new FishingRules.Context(zone, biome, time >= 13000 && time < 23000, world.hasStorm());
    }
}
