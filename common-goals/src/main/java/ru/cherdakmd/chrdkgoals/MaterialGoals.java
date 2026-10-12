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

    private MaterialGoals() {
    }
}
