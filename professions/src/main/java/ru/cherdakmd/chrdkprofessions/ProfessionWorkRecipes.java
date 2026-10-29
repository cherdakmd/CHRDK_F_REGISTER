package ru.cherdakmd.chrdkprofessions;

import java.util.Map;

/** Чистые, необратимые рецепты мастерской: без Bukkit, случайных бросков и опыта. */
final class ProfessionWorkRecipes {
    private static final Map<String, String> COOK = Map.ofEntries(
            Map.entry("BEEF", "COOKED_BEEF"), Map.entry("PORKCHOP", "COOKED_PORKCHOP"),
            Map.entry("CHICKEN", "COOKED_CHICKEN"), Map.entry("MUTTON", "COOKED_MUTTON"),
            Map.entry("RABBIT", "COOKED_RABBIT"), Map.entry("COD", "COOKED_COD"),
            Map.entry("SALMON", "COOKED_SALMON"), Map.entry("POTATO", "BAKED_POTATO"),
            Map.entry("KELP", "DRIED_KELP"));
    private static final Map<String, String> KILN = Map.ofEntries(
            Map.entry("COBBLESTONE", "STONE"), Map.entry("COBBLED_DEEPSLATE", "DEEPSLATE"),
            Map.entry("STONE", "SMOOTH_STONE"), Map.entry("SAND", "GLASS"),
            Map.entry("RED_SAND", "GLASS"), Map.entry("SANDSTONE", "SMOOTH_SANDSTONE"),
            Map.entry("RED_SANDSTONE", "SMOOTH_RED_SANDSTONE"), Map.entry("BASALT", "SMOOTH_BASALT"),
            Map.entry("CLAY_BALL", "BRICK"), Map.entry("NETHERRACK", "NETHER_BRICK"));
    private static final Map<String, String> FINISH = Map.ofEntries(
            Map.entry("STONE", "STONE_BRICKS"), Map.entry("GRANITE", "POLISHED_GRANITE"),
            Map.entry("DIORITE", "POLISHED_DIORITE"), Map.entry("ANDESITE", "POLISHED_ANDESITE"),
            Map.entry("COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE"),
            Map.entry("POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS"),
            Map.entry("DEEPSLATE_BRICKS", "DEEPSLATE_TILES"),
            Map.entry("BLACKSTONE", "POLISHED_BLACKSTONE"),
            Map.entry("POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS"),
            Map.entry("QUARTZ_BLOCK", "QUARTZ_BRICKS"), Map.entry("TUFF", "POLISHED_TUFF"),
            Map.entry("POLISHED_TUFF", "TUFF_BRICKS"));

    static String output(ProfessionMastery mastery, String input) {
        if (mastery == null || input == null) return null;
        return switch (mastery) {
            case CAMP_COOK, CHEF -> COOK.get(input);
            case KILN_MASTER -> KILN.get(input);
            case FINISHER -> FINISH.get(input);
            case GRENADIER -> input.equals("POTION") ? "SPLASH_POTION" : null;
            case DISTILLER -> input.equals("SPLASH_POTION") ? "LINGERING_POTION" : null;
            default -> null;
        };
    }

    static int fuel(int count) { return count <= 0 ? 0 : (int) (((long) count + 7) / 8); }

    static int repair(int maxDamage, int damage, int percent) {
        if (maxDamage <= 0 || damage <= 0 || percent <= 0) return 0;
        return (int) Math.min(damage, Math.max(1L, (long) maxDamage * Math.min(percent, 25) / 100));
    }

    static String repairMaterial(String material) {
        if (material == null) return null;
        if (material.equals("ELYTRA")) return "PHANTOM_MEMBRANE";
        if (material.equals("TURTLE_HELMET")) return "TURTLE_SCUTE";
        if (material.equals("SHIELD")) return "OAK_PLANKS";
        if (material.equals("BOW") || material.equals("CROSSBOW") || material.equals("FISHING_ROD")) return "STRING";
        if (material.equals("SHEARS") || material.equals("FLINT_AND_STEEL")) return "IRON_INGOT";
        // Материал из префикса недостаточен: DIAMOND/IRON_INGOT нельзя «чинить».
        if (!(material.endsWith("_SWORD") || material.endsWith("_PICKAXE") || material.endsWith("_AXE")
                || material.endsWith("_SHOVEL") || material.endsWith("_HOE") || material.endsWith("_HELMET")
                || material.endsWith("_CHESTPLATE") || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS"))) return null;
        if (material.startsWith("WOODEN_")) return "OAK_PLANKS";
        if (material.startsWith("STONE_")) return "COBBLESTONE";
        if (material.startsWith("IRON_") || material.startsWith("CHAINMAIL_")) return "IRON_INGOT";
        if (material.startsWith("GOLDEN_")) return "GOLD_INGOT";
        if (material.startsWith("DIAMOND_")) return "DIAMOND";
        if (material.startsWith("NETHERITE_")) return "NETHERITE_INGOT";
        if (material.startsWith("LEATHER_")) return "LEATHER";
        return null;
    }

    private ProfessionWorkRecipes() { }
}
