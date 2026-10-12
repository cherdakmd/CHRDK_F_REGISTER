package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;

import java.util.Set;

/** Ванильный «сокровенный» улов рыбалки: определение по материалу пойманного предмета. */
public final class TreasureLoot {

    private static final Set<Material> TREASURE = Set.of(
            Material.ENCHANTED_BOOK,
            Material.BOW,
            Material.FISHING_ROD,
            Material.SADDLE,
            Material.NAME_TAG,
            Material.NAUTILUS_SHELL,
            Material.LILY_PAD,
            Material.LEATHER_BOOTS,
            Material.STICK,
            Material.STRING,
            Material.POTION,          // water bottle — ловится как обычное зелье
            Material.BONE,
            Material.INK_SAC,
            Material.TRIPWIRE_HOOK,
            Material.BOWL,
            Material.ROTTEN_FLESH
    );

    public static boolean isTreasure(Material material) {
        return TREASURE.contains(material);
    }

    private TreasureLoot() {
    }
}
