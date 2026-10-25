package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;

import java.util.Set;

/**
 * Ванильная категория «сокровище» рыбалки. В Java Edition в неё входят ровно
 * шесть предметов — зачарованный лук, зачарованная книга, зачарованная
 * удочка, бирка, раковина наутилуса и седло (по 1/6 внутри категории, 5 %
 * улова без «Морской удачи»). Мусор (палка, кость, миска, гнилая плоть,
 * чернильный мешок, кувшинка, крюк, нить, кожаная обувь, бутылочка воды)
 * сокровищем не считается: раньше он тоже открывал бонусный артефакт, и
 * находка «с сокровища» падала с обычной палки.
 */
public final class TreasureLoot {

    /** Зачарованные варианты приходят теми же материалами. */
    private static final Set<Material> TREASURE = Set.of(
            Material.BOW,
            Material.ENCHANTED_BOOK,
            Material.FISHING_ROD,
            Material.NAME_TAG,
            Material.NAUTILUS_SHELL,
            Material.SADDLE
    );

    public static boolean isTreasure(Material material) {
        return material != null && TREASURE.contains(material);
    }

    /** Полный набор ванильных сокровищ (для тестов и диагностики). */
    public static Set<Material> vanillaTreasure() {
        return TREASURE;
    }

    private TreasureLoot() {
    }
}
