package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Material;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Опыт профессий: какие действия и предметы его дают (1.4.165).
 *
 * <p>Таблицы живут в коде, а не в конфиге: это баланс, и опечатка в YAML не
 * должна превращать ведро в источник уровней. Конфиг управляет только темпом —
 * суточным потолком, множителем и кулдауном смены.</p>
 *
 * <p>Правило отбора простое: опыт даёт <b>работа</b>, а не находка. Плавка и
 * крафт считают материалы, которые игрок реально потратил; варка — один такт
 * варочной стойки; добыча — сломанный блок; распил — снятую с камнереза
 * плитку. Ни одна запись не выдаёт алмазы, незерит или монеты: опыт —
 * единственная награда профессии.</p>
 *
 * <p>1.4.177 добавила два «повседневных» источника. До неё камнерез получал
 * опыт только за редкий крафт, а его главный инструмент — камнерез — не
 * считался вовсе: игрок выбирал ремесло и видел тишину. Поэтому добыча и
 * распил теперь в таблицах, и у каждого ремесла есть занятие, доступное с
 * первого дня без редких предметов.</p>
 */
public final class ProfessionCatalog {

    /** Опыт за одну завершённую варку: стойка превращает до трёх склянок. */
    public static final int BREW_XP = 8;
    /** Потолок значения в таблице: защита от случайной правки на порядок. */
    public static final int MAX_XP = 30;

    private static final Map<Profession, Map<Material, Integer>> CRAFT =
            new EnumMap<>(Profession.class);
    private static final Map<Profession, Map<Material, Integer>> SMELT =
            new EnumMap<>(Profession.class);
    /** Опыт за сломанный блок: повседневная работа каждого ремесла (1.4.177). */
    private static final Map<Profession, Map<Material, Integer>> MINING =
            new EnumMap<>(Profession.class);
    /** Опыт за распил на камнерезе: результат, а не исходный блок (1.4.177). */
    private static final Map<Profession, Map<Material, Integer>> CUT =
            new EnumMap<>(Profession.class);

    static {
        for (Profession profession : Profession.values()) {
            CRAFT.put(profession, new LinkedHashMap<>());
            SMELT.put(profession, new LinkedHashMap<>());
            MINING.put(profession, new LinkedHashMap<>());
            CUT.put(profession, new LinkedHashMap<>());
        }

        /* ---------------------------------- кузнец --------------------------------- */
        craft(Profession.SMITH, 4,
                Material.IRON_SWORD, Material.IRON_PICKAXE, Material.IRON_AXE,
                Material.IRON_SHOVEL, Material.IRON_HOE,
                Material.GOLDEN_SWORD, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
                Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE,
                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
        craft(Profession.SMITH, 5,
                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE,
                Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS);
        craft(Profession.SMITH, 8,
                Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE,
                Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE);
        craft(Profession.SMITH, 10,
                Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
        craft(Profession.SMITH, 12,
                Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
                Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE);
        craft(Profession.SMITH, 14,
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);
        craft(Profession.SMITH, 4,
                Material.SHIELD, Material.ANVIL, Material.MINECART,
                Material.IRON_DOOR, Material.IRON_TRAPDOOR, Material.COMPASS);
        craft(Profession.SMITH, 5,
                Material.HOPPER, Material.CAULDRON, Material.SHEARS,
                Material.FLINT_AND_STEEL, Material.CROSSBOW, Material.CLOCK);
        craft(Profession.SMITH, 3, Material.BUCKET, Material.LANTERN, Material.SOUL_LANTERN);
        craft(Profession.SMITH, 2, Material.IRON_BARS, Material.IRON_CHAIN);
        craft(Profession.SMITH, 1, Material.RAIL);
        smelt(Profession.SMITH, 3, Material.IRON_INGOT, Material.GOLD_INGOT);
        smelt(Profession.SMITH, 4, Material.NETHERITE_SCRAP);
        smelt(Profession.SMITH, 1, Material.COPPER_INGOT);

        /* --------------------------------- алхимик --------------------------------- */
        craft(Profession.ALCHEMIST, 8, Material.BREWING_STAND);
        craft(Profession.ALCHEMIST, 3,
                Material.MAGMA_CREAM, Material.FERMENTED_SPIDER_EYE);
        craft(Profession.ALCHEMIST, 2,
                Material.BLAZE_POWDER, Material.GLISTERING_MELON_SLICE,
                Material.GOLDEN_CARROT);
        craft(Profession.ALCHEMIST, 1, Material.SUGAR, Material.GLASS_BOTTLE);

        /* ---------------------------------- повар ---------------------------------- */
        craft(Profession.COOK, 20, Material.ENCHANTED_GOLDEN_APPLE);
        craft(Profession.COOK, 10, Material.GOLDEN_APPLE);
        craft(Profession.COOK, 6, Material.CAKE);
        craft(Profession.COOK, 4, Material.PUMPKIN_PIE, Material.RABBIT_STEW);
        craft(Profession.COOK, 3,
                Material.BREAD, Material.MUSHROOM_STEW, Material.BEETROOT_SOUP,
                Material.GOLDEN_CARROT);
        craft(Profession.COOK, 1, Material.COOKIE);
        smelt(Profession.COOK, 2,
                Material.COOKED_PORKCHOP, Material.COOKED_BEEF, Material.COOKED_CHICKEN,
                Material.COOKED_MUTTON, Material.COOKED_RABBIT,
                Material.COOKED_COD, Material.COOKED_SALMON);
        smelt(Profession.COOK, 1,
                Material.BAKED_POTATO, Material.DRIED_KELP, Material.POPPED_CHORUS_FRUIT);

        /* -------------------------------- камнерез --------------------------------- */
        craft(Profession.MASON, 4,
                Material.SEA_LANTERN, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        craft(Profession.MASON, 3, Material.GLOWSTONE);
        craft(Profession.MASON, 2, Material.BRICKS, Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ);
        craft(Profession.MASON, 1,
                Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.CHISELED_STONE_BRICKS, Material.SMOOTH_STONE,
                Material.STONE_BRICK_SLAB, Material.STONE_BRICK_STAIRS,
                Material.STONE_BRICK_WALL, Material.GLASS_PANE);
        smelt(Profession.MASON, 1,
                Material.STONE, Material.SMOOTH_STONE, Material.GLASS,
                Material.BRICK, Material.TERRACOTTA, Material.NETHER_BRICK);

        /* -------------------------------- добыча ---------------------------------- */
        // Повседневная работа: то, что игрок ломает каждый день. Значения те же,
        // что у плавки — один блок это один шаг, а не награда за находку.
        mine(Profession.SMITH, 4, Material.ANCIENT_DEBRIS);
        mine(Profession.SMITH, 2,
                Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
                Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
                Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
                Material.NETHER_QUARTZ_ORE);
        mine(Profession.ALCHEMIST, 2, Material.AMETHYST_CLUSTER);
        mine(Profession.ALCHEMIST, 1,
                Material.GLOWSTONE, Material.MAGMA_BLOCK, Material.NETHER_WART);
        mine(Profession.COOK, 1,
                Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
                Material.PUMPKIN, Material.MELON, Material.SUGAR_CANE, Material.COCOA,
                Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
                Material.KELP, Material.KELP_PLANT, Material.SEA_PICKLE);
        mine(Profession.MASON, 1,
                Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE,
                Material.COBBLED_DEEPSLATE, Material.ANDESITE, Material.DIORITE,
                Material.GRANITE, Material.TUFF, Material.CALCITE, Material.BASALT,
                Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.DRIPSTONE_BLOCK,
                Material.CLAY, Material.SANDSTONE, Material.RED_SANDSTONE,
                Material.NETHERRACK);

        /* -------------------------------- камнерез --------------------------------- */
        // Камнерез не поднимает CraftItemEvent: результат режется на месте. Поэтому
        // у камнереза своя таблица — по тому, что игрок унёс с пилы (1.4.177).
        cut(Profession.MASON, 2,
                Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE,
                Material.QUARTZ_BRICKS, Material.PURPUR_BLOCK, Material.PURPUR_PILLAR);
        cut(Profession.MASON, 1,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                Material.CRACKED_STONE_BRICKS, Material.CHISELED_STONE_BRICKS,
                Material.SMOOTH_STONE, Material.STONE_SLAB, Material.STONE_STAIRS,
                Material.STONE_BRICK_SLAB, Material.STONE_BRICK_STAIRS,
                Material.STONE_BRICK_WALL, Material.COBBLESTONE_SLAB,
                Material.COBBLESTONE_STAIRS, Material.COBBLESTONE_WALL,
                Material.MOSSY_COBBLESTONE, Material.POLISHED_ANDESITE,
                Material.POLISHED_ANDESITE_SLAB, Material.POLISHED_ANDESITE_STAIRS,
                Material.POLISHED_DIORITE, Material.POLISHED_DIORITE_SLAB,
                Material.POLISHED_GRANITE, Material.POLISHED_GRANITE_STAIRS,
                Material.ANDESITE_SLAB, Material.DIORITE_STAIRS, Material.GRANITE_WALL,
                Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES,
                Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_SLAB,
                Material.DEEPSLATE_BRICK_STAIRS, Material.DEEPSLATE_TILE_WALL,
                Material.COBBLED_DEEPSLATE_SLAB, Material.COBBLED_DEEPSLATE_STAIRS,
                Material.COBBLED_DEEPSLATE_WALL, Material.CHISELED_DEEPSLATE,
                Material.TUFF_BRICKS, Material.POLISHED_TUFF, Material.TUFF_SLAB,
                Material.TUFF_STAIRS, Material.TUFF_WALL, Material.CHISELED_TUFF,
                Material.TUFF_BRICK_SLAB, Material.TUFF_BRICK_STAIRS,
                Material.TUFF_BRICK_WALL, Material.SANDSTONE_SLAB,
                Material.CUT_SANDSTONE, Material.RED_SANDSTONE_STAIRS,
                Material.CUT_RED_SANDSTONE, Material.QUARTZ_BLOCK, Material.QUARTZ_SLAB,
                Material.QUARTZ_STAIRS, Material.QUARTZ_PILLAR,
                Material.CHISELED_QUARTZ_BLOCK, Material.SMOOTH_QUARTZ_SLAB,
                Material.SMOOTH_QUARTZ_STAIRS, Material.END_STONE_BRICKS,
                Material.END_STONE_BRICK_SLAB, Material.PURPUR_SLAB,
                Material.PURPUR_STAIRS, Material.PRISMARINE_BRICK_SLAB,
                Material.DARK_PRISMARINE_SLAB, Material.BRICKS, Material.BRICK_SLAB,
                Material.BRICK_STAIRS, Material.BRICK_WALL, Material.MUD_BRICKS,
                Material.MUD_BRICK_SLAB, Material.MUD_BRICK_STAIRS,
                Material.MUD_BRICK_WALL, Material.PACKED_MUD);
    }

    private static void craft(Profession profession, int xp, Material... materials) {
        put(CRAFT, profession, xp, materials);
    }

    private static void smelt(Profession profession, int xp, Material... materials) {
        put(SMELT, profession, xp, materials);
    }

    private static void mine(Profession profession, int xp, Material... materials) {
        put(MINING, profession, xp, materials);
    }

    private static void cut(Profession profession, int xp, Material... materials) {
        put(CUT, profession, xp, materials);
    }

    private static void put(Map<Profession, Map<Material, Integer>> table, Profession profession,
                            int xp, Material... materials) {
        int value = Math.clamp(xp, 1, MAX_XP);
        for (Material material : materials) {
            if (material != null && material != Material.AIR) {
                table.get(profession).put(material, value);
            }
        }
    }

    /* ---------------------------------- доступ --------------------------------- */

    /** Опыт за крафт одного предмета (0 — не работа этой профессии). */
    public static int craftXp(Profession profession, Material material) {
        return lookup(CRAFT, profession, material);
    }

    /** Опыт за выплавку одного предмета (0 — не работа этой профессии). */
    public static int smeltXp(Profession profession, Material material) {
        return lookup(SMELT, profession, material);
    }

    /** Опыт за сломанный блок (0 — этот блок не работа выбранного ремесла). */
    public static int miningXp(Profession profession, Material material) {
        return lookup(MINING, profession, material);
    }

    /** Опыт за распил на камнерезе: считается то, что унесли с пилы. */
    public static int cutXp(Profession profession, Material material) {
        return lookup(CUT, profession, material);
    }

    /** Опыт за завершённую варку: только у алхимика. */
    public static int brewXp(Profession profession) {
        return profession == Profession.ALCHEMIST ? BREW_XP : 0;
    }

    private static int lookup(Map<Profession, Map<Material, Integer>> table, Profession profession,
                              Material material) {
        if (profession == null || material == null) {
            return 0;
        }
        Map<Material, Integer> row = table.get(profession);
        return row == null ? 0 : row.getOrDefault(material, 0);
    }

    /** Копия таблицы крафта — только для проверок и админской справки. */
    public static Map<Material, Integer> craftTable(Profession profession) {
        return copy(CRAFT, profession);
    }

    /** Копия таблицы плавки — только для проверок и админской справки. */
    public static Map<Material, Integer> smeltTable(Profession profession) {
        return copy(SMELT, profession);
    }

    /** Копия таблицы добычи — только для проверок и админской справки. */
    public static Map<Material, Integer> miningTable(Profession profession) {
        return copy(MINING, profession);
    }

    /** Копия таблицы камнереза — только для проверок и админской справки. */
    public static Map<Material, Integer> cutTable(Profession profession) {
        return copy(CUT, profession);
    }

    private static Map<Material, Integer> copy(Map<Profession, Map<Material, Integer>> table,
                                               Profession profession) {
        Map<Material, Integer> row = profession == null ? null : table.get(profession);
        return row == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    private ProfessionCatalog() {
    }
}
