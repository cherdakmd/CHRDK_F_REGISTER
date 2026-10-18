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
    void blockMaterialsUseBlockLanguageNamespace() {
        assertEquals("<lang:block.minecraft.stone>",
                TierInfoFormat.languageTag(Material.STONE));
    }

    @Test
    void airAndNullAreEmpty() {
        assertEquals("", TierInfoFormat.languageTag(null));
        assertEquals("", TierInfoFormat.languageTag(Material.AIR));
    }
}
