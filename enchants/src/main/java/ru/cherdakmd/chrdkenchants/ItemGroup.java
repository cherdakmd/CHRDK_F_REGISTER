package ru.cherdakmd.chrdkenchants;

/** Типы предметов, на которые накладываются кастомные чары, и их классификация по имени материала. */
public enum ItemGroup {
    SWORD, AXE, BOW, CROSSBOW,
    HELMET, CHESTPLATE, LEGGINGS, BOOTS,
    PICKAXE, SHOVEL, HOE;

    /** Классифицирует имя материала (например "DIAMOND_SWORD") — без загрузки Bukkit-классов. */
    public static ItemGroup classify(String materialName) {
        String name = materialName == null ? "" : materialName.toUpperCase(java.util.Locale.ROOT);
        if (name.endsWith("_SWORD")) return SWORD;
        if (name.endsWith("_PICKAXE")) return PICKAXE;
        if (name.endsWith("_SHOVEL")) return SHOVEL;
        if (name.endsWith("_AXE")) return AXE;
        if (name.endsWith("_HOE")) return HOE;
        if (name.equals("BOW")) return BOW;
        if (name.equals("CROSSBOW")) return CROSSBOW;
        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET")) return HELMET;
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) return CHESTPLATE;
        if (name.endsWith("_LEGGINGS")) return LEGGINGS;
        if (name.endsWith("_BOOTS")) return BOOTS;
        return null;
    }

    /** Ключ сообщения с человекочитаемым именем группы. */
    public boolean isMelee() {
        return this == SWORD || this == AXE;
    }
    public String labelKey() {
        return switch (this) {
            case SWORD, AXE -> "item-sword";
            case BOW, CROSSBOW -> "item-bow";
            case HELMET -> "item-helmet";
            case CHESTPLATE -> "item-chestplate";
            case LEGGINGS -> "item-leggings";
            case BOOTS -> "item-boots";
            case PICKAXE, SHOVEL, HOE -> "item-tool";
        };
    }
}
