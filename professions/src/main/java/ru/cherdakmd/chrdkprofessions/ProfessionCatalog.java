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
 * варочной стойки. Ни одна запись не выдаёт алмазы, незерит или монеты: опыт —
 * единственная награда профессии.</p>
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

    static {
        for (Profession profession : Profession.values()) {
            CRAFT.put(profession, new LinkedHashMap<>());
            SMELT.put(profession, new LinkedHashMap<>());
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
        craft(Profession.SMITH, 2, Material.IRON_BARS, Material.CHAIN);
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
    }

    private static void craft(Profession profession, int xp, Material... materials) {
        put(CRAFT, profession, xp, materials);
    }

    private static void smelt(Profession profession, int xp, Material... materials) {
        put(SMELT, profession, xp, materials);
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

    private static Map<Material, Integer> copy(Map<Profession, Map<Material, Integer>> table,
                                               Profession profession) {
        Map<Material, Integer> row = profession == null ? null : table.get(profession);
        return row == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    private ProfessionCatalog() {
    }
}
