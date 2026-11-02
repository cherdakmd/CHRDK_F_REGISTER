package ru.cherdakmd.chrdkregister.api.exploration;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** API37. Stable task IDs; biome keys come from the server registry, not an outdated enum list. */
public final class ExplorationCatalog {
    private ExplorationCatalog() { }
    public enum Kind { CHUNKS, WALK, MINE, KILL, BREED, FISH, STUDY, ACTIVE }
    public record Task(String id, Kind kind, int target, Set<String> matches, String textKey) {
        public Task { matches = Set.copyOf(matches); }
        public boolean accepts(String key) { return matches.contains("*") || matches.contains(key.toLowerCase(Locale.ROOT)); }
    }
    public static boolean required(String key) {
        return key != null && key.matches("minecraft:[a-z0-9_]+") && !key.equals("minecraft:the_void");
    }
    private static Task task(String id, Kind kind, int goal, String text, String... keys) {
        return new Task(id, kind, goal, Set.of(keys), text);
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, List<Task>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    public static List<Task> tasks(String biome) {
        if (!required(biome)) throw new IllegalArgumentException("Not a required biome: " + biome);
        return CACHE.computeIfAbsent(biome, ExplorationCatalog::buildTasks);
    }
    /** Five tasks IN ADDITION to visiting. No PvP, Warden kills, teleport or creative objectives. */
    private static List<Task> buildTasks(String biome) {
        String name = biome.substring("minecraft:".length());
        Task geology = task("geology", Kind.MINE, 32, "atlas-geology", "*");
        Task ecology = task("ecology", Kind.BREED, 3, "atlas-breed", "cow", "sheep", "pig", "chicken");
        if (name.contains("ocean") || name.contains("river") || name.equals("beach") || name.equals("snowy_beach")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "sand", "gravel", "clay", "stone", "ice");
            ecology = task("ecology", Kind.FISH, 5, "atlas-fish", "cod", "salmon", "pufferfish", "tropical_fish");
        } else if (name.equals("the_end") || name.startsWith("end_") || name.equals("small_end_islands")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "end_stone");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "end_stone", "chorus_plant", "chorus_flower");
        } else if (Set.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas").contains(name)) {
            geology = switch (name) {
                case "basalt_deltas" -> task("geology", Kind.MINE, 32, "atlas-geology", "basalt", "blackstone");
                case "soul_sand_valley" -> task("geology", Kind.MINE, 32, "atlas-geology", "soul_sand", "soul_soil");
                case "crimson_forest" -> task("geology", Kind.MINE, 32, "atlas-geology", "crimson_stem", "crimson_nylium", "netherrack");
                case "warped_forest" -> task("geology", Kind.MINE, 32, "atlas-geology", "warped_stem", "warped_nylium", "netherrack");
                default -> task("geology", Kind.MINE, 32, "atlas-geology", "netherrack", "nether_quartz_ore");
            };
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "netherrack", "basalt", "blackstone", "soul_sand", "soul_soil", "crimson_nylium", "warped_nylium");
        } else if (name.contains("desert") || name.contains("badlands")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "sand", "sandstone", "red_sand", "red_sandstone", "terracotta", "orange_terracotta", "brown_terracotta", "white_terracotta");
            ecology = task("ecology", Kind.KILL, 5, "atlas-kill", "husk", "zombie", "skeleton", "spider", "creeper");
        } else if (name.equals("deep_dark")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "deepslate", "sculk");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "sculk", "sculk_vein", "sculk_sensor", "sculk_catalyst");
        } else if (name.equals("lush_caves")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "clay", "moss_block", "stone");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "moss_block", "azalea", "flowering_azalea", "glow_lichen", "spore_blossom", "small_dripleaf", "big_dripleaf");
        } else if (name.equals("dripstone_caves")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "stone", "deepslate", "dripstone_block");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "pointed_dripstone", "dripstone_block");
        } else if (name.equals("mushroom_fields")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "mycelium", "dirt", "mushroom_stem", "brown_mushroom_block", "red_mushroom_block");
            ecology = task("ecology", Kind.BREED, 3, "atlas-breed", "mooshroom");
        } else if (name.contains("frozen") || name.contains("snow") || name.equals("ice_spikes") || name.equals("grove")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "snow", "snow_block", "ice", "packed_ice", "stone", "dirt");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "snow", "snow_block", "ice", "packed_ice", "spruce_log", "spruce_leaves");
        } else if (name.contains("peaks") || name.equals("stony_shore") || name.equals("windswept_hills") || name.equals("windswept_gravelly_hills")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "stone", "gravel", "calcite", "snow_block");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "stone", "calcite", "gravel", "snow_block", "ice");
        } else if (name.contains("forest") || name.contains("taiga") || name.contains("jungle") || name.equals("cherry_grove") || name.equals("pale_garden") || name.contains("swamp") || name.contains("savanna")) {
            geology = task("geology", Kind.MINE, 32, "atlas-geology", "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log", "bamboo_block");
            ecology = task("ecology", Kind.STUDY, 8, "atlas-study", "oak_leaves", "birch_leaves", "spruce_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves", "pale_oak_leaves", "mud", "bamboo");
        }
        return List.of(task("survey", Kind.CHUNKS, 8, "atlas-survey"),
                task("path", Kind.WALK, 60_000, "atlas-path"), geology, ecology,
                task("camp", Kind.ACTIVE, 300, "atlas-camp"));
    }
}
