package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Форматирование /tiers info (1.4.84). */
class TierInfoFormatTest {

    @Test
    void sourceKeysAreNormalized() {
        assertEquals("source-craft", TierInfoFormat.sourceKey("craft"));
        assertEquals("source-drop", TierInfoFormat.sourceKey(" DROP "));
        assertEquals("source-give", TierInfoFormat.sourceKey("Give"));
        assertEquals("source-unknown", TierInfoFormat.sourceKey(null));
        assertEquals("source-unknown", TierInfoFormat.sourceKey(""));
        assertEquals("source-unknown", TierInfoFormat.sourceKey("auction"));
    }

    @Test
    void itemMaterialsUseItemLanguageNamespace() {
        assertEquals("<lang:item.minecraft.diamond_sword>",
                TierInfoFormat.languageTag(Material.DIAMOND_SWORD));
        assertEquals("<lang:item.minecraft.netherite_pickaxe>",
                TierInfoFormat.languageTag(Material.NETHERITE_PICKAXE));
        assertEquals("<lang:item.minecraft.bow>",
                TierInfoFormat.languageTag(Material.BOW));
    }

    @Test
    void materialKeyPathIsUsedVerbatim() {
        // Путь — нижний регистр имени материала.
        assertEquals("<lang:item.minecraft.amethyst_shard>",
                TierInfoFormat.languageTag(Material.AMETHYST_SHARD));
    }

    @Test
    void blockShapedUtilitiesUseBlockNamespace() {
        // 1.4.142: подсолнух, фонарь душ, шипы и череп лежат в инвентаре
        // предметом, но в клиенте называются блоком — иначе игрок увидел бы
        // сырой ключ «item.minecraft.soul_lantern».
        assertEquals("<lang:block.minecraft.sunflower>",
                TierInfoFormat.languageTag(Material.SUNFLOWER));
        assertEquals("<lang:block.minecraft.soul_lantern>",
                TierInfoFormat.languageTag(Material.SOUL_LANTERN));
        assertEquals("<lang:block.minecraft.pointed_dripstone>",
                TierInfoFormat.languageTag(Material.POINTED_DRIPSTONE));
        assertEquals("<lang:block.minecraft.wither_skeleton_skull>",
                TierInfoFormat.languageTag(Material.WITHER_SKELETON_SKULL));
        // Остальная утилита — обычные предметы.
        assertEquals("<lang:item.minecraft.shield>", TierInfoFormat.languageTag(Material.SHIELD));
        assertEquals("<lang:item.minecraft.goat_horn>",
                TierInfoFormat.languageTag(Material.GOAT_HORN));
        assertEquals("<lang:item.minecraft.totem_of_undying>",
                TierInfoFormat.languageTag(Material.TOTEM_OF_UNDYING));
    }

    @Test
    void airAndNullAreEmpty() {
        assertEquals("", TierInfoFormat.languageTag(null));
        assertEquals("", TierInfoFormat.languageTag(Material.AIR));
        assertEquals("", TierInfoFormat.languageTag(Material.CAVE_AIR));
        assertEquals("", TierInfoFormat.languageTag(Material.VOID_AIR));
    }
}
