package ru.cherdakmd.chrdkskills;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Какие блоки кормят какой навык.
 *
 * <p>Таблица статическая и не зависит от конфига: навык определяется типом
 * блока, а не его происхождением. Опыт за конкретный блок одинаковый — разница
 * в цене действия задаётся конфигом навыка, а редкие руды ценнее только тем,
 * что их меньше.</p>
 *
 * <p>Сбор урожая считается только для созревших культур: иначе игрок качал бы
 * земледелие, ломая ростки. Автопосадка возвращает культуру на грядку в
 * нулевом возрасте.</p>
 */
public final class SkillMaterials {

    private static final Set<Material> MINING = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.TUFF, Material.CALCITE, Material.NETHERRACK,
            Material.BLACKSTONE, Material.BASALT, Material.SMOOTH_BASALT,
            Material.END_STONE, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS);

    private static final Set<Material> DIGGING = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.MUD, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.SNOW_BLOCK, Material.MOSS_BLOCK);

    private static final Set<Material> FARMING = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.PUMPKIN, Material.MELON,
            Material.SUGAR_CANE, Material.BAMBOO, Material.SWEET_BERRY_BUSH,
            Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.KELP,
            Material.KELP_PLANT, Material.SEA_PICKLE);

    private SkillMaterials() {
    }

    /** Навык, который качает разрушение этого блока; {@code null} — блок вне таблиц. */
    public static SkillType blockSkill(Material material) {
        if (material == null) {
            return null;
        }
        String name = material.name();
        if (name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE")
                || name.startsWith("STRIPPED_")) {
            return SkillType.WOODCUTTING;
        }
        if (MINING.contains(material)) {
            return SkillType.MINING;
        }
        if (DIGGING.contains(material)) {
            return SkillType.DIGGING;
        }
        if (FARMING.contains(material)) {
            return SkillType.FARMING;
        }
        return null;
    }

    /**
     * Созревшая ли культура. Блок без возраста (бамбук, тростник, тыква)
     * считается созревшим всегда; для {@link Ageable} нужен максимальный возраст.
     */
    public static boolean isRipe(Block block) {
        if (block == null) {
            return false;
        }
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return true;
    }

    /** Культура, которую автопосадка возвращает на грядку ({}null} — не сажаем). */
    public static Material replantOf(Material material) {
        if (material == null) {
            return null;
        }
        return switch (material) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROTS;
            case POTATOES -> Material.POTATOES;
            case BEETROOTS -> Material.BEETROOTS;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA -> Material.COCOA;
            default -> null;
        };
    }
}
