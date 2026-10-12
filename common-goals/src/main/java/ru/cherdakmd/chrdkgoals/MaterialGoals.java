package ru.cherdakmd.chrdkgoals;

import org.bukkit.Material;

import java.util.Set;

/** Разметка ванильных материалов под метрики целей. Чистый класс — тестируется без Bukkit. */
public final class MaterialGoals {

    private static final Set<Material> ORES = Set.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.PUMPKIN, Material.MELON
    );

    /** Древесина для рубки: брёвна, обтёсанные брёвна, блоки древесины, стебли и гифы. */
    private static final Set<Material> LOGS = Set.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG,
            Material.CHERRY_LOG, Material.PALE_OAK_LOG,
            Material.STRIPPED_OAK_LOG, Material.STRIPPED_SPRUCE_LOG, Material.STRIPPED_BIRCH_LOG,
            Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_ACACIA_LOG,
            Material.STRIPPED_DARK_OAK_LOG, Material.STRIPPED_MANGROVE_LOG,
            Material.STRIPPED_CHERRY_LOG, Material.STRIPPED_PALE_OAK_LOG,
            Material.OAK_WOOD, Material.SPRUCE_WOOD, Material.BIRCH_WOOD, Material.JUNGLE_WOOD,
            Material.ACACIA_WOOD, Material.DARK_OAK_WOOD, Material.MANGROVE_WOOD,
            Material.CHERRY_WOOD, Material.PALE_OAK_WOOD,
            Material.STRIPPED_OAK_WOOD, Material.STRIPPED_SPRUCE_WOOD,
            Material.STRIPPED_BIRCH_WOOD, Material.STRIPPED_JUNGLE_WOOD,
            Material.STRIPPED_ACACIA_WOOD, Material.STRIPPED_DARK_OAK_WOOD,
            Material.STRIPPED_MANGROVE_WOOD, Material.STRIPPED_CHERRY_WOOD,
            Material.STRIPPED_PALE_OAK_WOOD,
            Material.CRIMSON_STEM, Material.WARPED_STEM,
            Material.STRIPPED_CRIMSON_STEM, Material.STRIPPED_WARPED_STEM,
            Material.CRIMSON_HYPHAE, Material.WARPED_HYPHAE,
            Material.STRIPPED_CRIMSON_HYPHAE, Material.STRIPPED_WARPED_HYPHAE
    );

    /** Копаемый грунт: земля, песок, гравий и родственные мягкие блоки. */
    private static final Set<Material> DIGGABLE = Set.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.DIRT_PATH, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.FARMLAND,
            Material.MUD, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.SNOW_BLOCK
    );

    /** Материалы, считаемые алмазным содержанием для взноса в котёл; значение = эквивалент в алмазах. */
    public static int diamondValue(Material material) {
        return switch (material) {
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case DIAMOND_BLOCK -> 9;
            default -> 0;
        };
    }

    public static boolean isOre(Material material) {
        return ORES.contains(material);
    }

    public static boolean isCrop(Material material) {
        return CROPS.contains(material);
    }

    public static boolean isLog(Material material) {
        return LOGS.contains(material);
    }

    public static boolean isDiggable(Material material) {
        return DIGGABLE.contains(material);
    }

    private MaterialGoals() {
    }
}
