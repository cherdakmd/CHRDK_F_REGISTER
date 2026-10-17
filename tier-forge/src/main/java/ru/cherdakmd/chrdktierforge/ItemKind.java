package ru.cherdakmd.chrdktierforge;

/**
 * Классификация подходящих предметов по имени материала (без Bukkit): броня/оружие/инструменты.
 */
public enum ItemKind {
    SWORD, AXE, BOW, CROSSBOW, TRIDENT,
    HELMET, CHESTPLATE, LEGGINGS, BOOTS,
    PICKAXE, SHOVEL, HOE;

    public static ItemKind classify(String materialName) {
        String name = materialName == null ? "" : materialName.toUpperCase(java.util.Locale.ROOT);
        if (name.endsWith("_SWORD")) return SWORD;
        if (name.endsWith("_PICKAXE")) return PICKAXE;
        if (name.endsWith("_SHOVEL")) return SHOVEL;
        if (name.endsWith("_AXE")) return AXE;
        if (name.endsWith("_HOE")) return HOE;
        if (name.equals("BOW")) return BOW;
        if (name.equals("CROSSBOW")) return CROSSBOW;
        if (name.equals("TRIDENT")) return TRIDENT;
        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET")) return HELMET;
        if (name.endsWith("_CHESTPLATE")) return CHESTPLATE;
        if (name.endsWith("_LEGGINGS")) return LEGGINGS;
        if (name.endsWith("_BOOTS")) return BOOTS;
        return null;
    }

    public boolean isArmor() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }

    public boolean isWeapon() {
        return this == SWORD || this == AXE || this == TRIDENT
                || this == BOW || this == CROSSBOW;
    }

    public boolean isTool() {
        return this == PICKAXE || this == SHOVEL || this == HOE || this == AXE;
    }
}
