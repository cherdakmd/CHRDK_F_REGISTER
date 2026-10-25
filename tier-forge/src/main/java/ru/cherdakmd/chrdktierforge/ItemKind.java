package ru.cherdakmd.chrdktierforge;

import java.util.Locale;
import java.util.Set;

/**
 * Классификация подходящих предметов по имени материала (без Bukkit):
 * броня, оружие, инструменты и утилита.
 *
 * <p>С 1.4.142 к трём боевым ролям добавилась четвёртая — {@link #UTILITY}.
 * Это вещи, которые носят в руке, но которыми не бьют и не копают: щит, рог,
 * осколок эха, сердце моря, фонарь душ, часы, тотем. Раньше {@code classify}
 * возвращал для них {@code null}, и тир-система их просто не замечала: находка
 * арсенала с шансом 100 % оставалась без тира. Роль нужна именно для того,
 * чтобы утилита получала тир, но <b>не</b> получала чужих бонусов — ни урона,
 * ни скорости добычи, ни защиты.</p>
 *
 * <p>Список материалов закрытый: угадывать «утилиту» по имени нельзя, иначе
 * тир полез бы на еду, вёдра и прочий инвентарь. Материал, которого нет в
 * списке, по-прежнему не тир-предмет.</p>
 */
public enum ItemKind {
    SWORD, AXE, BOW, CROSSBOW, TRIDENT,
    HELMET, CHESTPLATE, LEGGINGS, BOOTS,
    PICKAXE, SHOVEL, HOE,
    /** Носимая утилита: тир даёт ей скорость и удачу, но не боевой профиль. */
    UTILITY;

    /**
     * Материалы утилиты. Здесь ровно те вещи, что встречаются как находки
     * арсенала (щит, рога, осколок эха, сердце моря, фонарь душ, часы, шипы,
     * некромантский череп, подсолнух), плюс компас, компас восстановления и
     * тотем — их тоже носят в руке.
     */
    private static final Set<String> UTILITY_MATERIALS = Set.of(
            "SHIELD", "GOAT_HORN", "ECHO_SHARD", "HEART_OF_THE_SEA", "SOUL_LANTERN",
            "CLOCK", "COMPASS", "RECOVERY_COMPASS", "SUNFLOWER", "POINTED_DRIPSTONE",
            "WITHER_SKELETON_SKULL", "TOTEM_OF_UNDYING");

    public static ItemKind classify(String materialName) {
        String name = materialName == null ? "" : materialName.toUpperCase(Locale.ROOT);
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
        if (UTILITY_MATERIALS.contains(name)) return UTILITY;
        return null;
    }

    public boolean isArmor() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }

    /** Оружие ближнего боя: меч, топор (он же инструмент) и трезубец. */
    public boolean isMelee() {
        return this == SWORD || this == AXE || this == TRIDENT;
    }

    /** Дальний бой: лук и арбалет. Их бонусы работают только на стрелы. */
    public boolean isRanged() {
        return this == BOW || this == CROSSBOW;
    }

    public boolean isWeapon() {
        return isMelee() || isRanged();
    }

    public boolean isTool() {
        return this == PICKAXE || this == SHOVEL || this == HOE || this == AXE;
    }

    /**
     * Носимая утилита (1.4.142): щит, рог, фонарь, тотем. Не оружие, не броня
     * и не инструмент — у неё нет ни удара, ни забоя, ни защиты.
     */
    public boolean isUtility() {
        return this == UTILITY;
    }
}
