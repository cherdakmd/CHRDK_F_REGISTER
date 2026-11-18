package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaBookMergeTest {

    @Test
    void fortunateSupportsCustomPickaxeMaterialButNotSword() {
        EnchantDef fortunate = EnchantRegistry.byId("fortunate");
        assertTrue(fortunate.supports("IRON_PICKAXE"));
        assertTrue(fortunate.supports("NETHERITE_PICKAXE"));
        assertFalse(fortunate.supports("DIAMOND_SWORD"));
    }

    @Test
    void nativeNullFallbackDoesNotBypassConflictsOrTooExpensive() throws Exception {
        String listener = source("AnvilApplyListener");
        assertTrue(listener.contains("vanilla == null && !pureBook && vanillaCost < 40"));
        assertTrue(listener.contains("manualVanilla == null && !AnvilPrice.nativeCompatible"));
        assertTrue(listener.contains("manualVanilla.nativeCost()"));
        assertTrue(listener.contains("pureBook || manualVanilla != null"));

        String merge = source("VanillaBookMerge");
        assertTrue(merge.contains("enchantment.canEnchantItem(result)"));
        assertTrue(merge.contains("existing.conflictsWith(enchantment)"));
        assertTrue(merge.contains("enchantment.conflictsWith(existing)"));
        assertTrue(merge.contains("Math.max(levelCap, enchantment.getMaxLevel())"),
                "потолок слияния — rules.vanilla-level-cap, но не ниже ванильного максимума");
        assertTrue(merge.contains("addStoredEnchant(enchantment, merged.get(enchantment), true)"),
                "надванильные уровни допустимы: потолок уже применён в merge");
        assertTrue(merge.contains("meta.addEnchant(enchantment, merged.get(enchantment), true)"),
                "то же для цели-вещи");
        assertTrue(merge.contains("source.clone()"));
    }

    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
}
