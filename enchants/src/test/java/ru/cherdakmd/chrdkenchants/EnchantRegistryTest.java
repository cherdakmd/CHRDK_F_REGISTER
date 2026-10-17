package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Каталог: ровно 30 чар, уникальные id, валидные группы поддержки и редкости. */
class EnchantRegistryTest {

    @Test
    void thirtyUniqueEnchants() {
        List<EnchantDef> all = EnchantRegistry.all();
        assertEquals(30, all.size());
        Set<String> ids = new HashSet<>();
        all.forEach(def -> {
            assertTrue(ids.add(def.id()), "дубликат id " + def.id());
            assertEquals(def.id(), def.id().toLowerCase(Locale.ROOT));
            assertTrue(def.maxLevel() >= 1 && def.maxLevel() <= 3);
            assertNotNull(def.supports());
            assertTrue(!def.supports().isEmpty());
        });
    }

    @Test
    void categoriesAreCovered() {
        Set<ItemGroup> used = new HashSet<>();
        EnchantRegistry.all().forEach(def -> used.addAll(def.supports()));
        for (ItemGroup group : List.of(ItemGroup.SWORD, ItemGroup.BOW, ItemGroup.HELMET,
                ItemGroup.CHESTPLATE, ItemGroup.LEGGINGS, ItemGroup.BOOTS, ItemGroup.PICKAXE)) {
            assertTrue(used.contains(group), "нет чары для " + group);
        }
        assertEquals(9, EnchantRegistry.all().stream().filter(def ->
                def.supports().contains(ItemGroup.SWORD)).count(), "девять ближних чар");
        assertEquals(4, EnchantRegistry.all().stream().filter(def ->
                def.supports().contains(ItemGroup.PICKAXE)).count(), "четыре кирковых");
    }

    @Test
    void rareDistributionSanity() {
        long legendary = EnchantRegistry.all().stream()
                .filter(def -> def.rarity() == EnchantDef.Rarity.LEGENDARY).count();
        long common = EnchantRegistry.all().stream()
                .filter(def -> def.rarity() == EnchantDef.Rarity.COMMON).count();
        assertTrue(legendary >= 5 && legendary <= 8, "легендарных должно быть горстка");
        assertTrue(common >= 3 && common <= 7, "обычных тоже немало");
        assertEquals(4, EnchantRegistry.all().stream()
                .map(EnchantDef::rarity).distinct().count(), "все 4 редкости представлены");
    }

    @Test
    void classifierBasics() {
        assertEquals(ItemGroup.classify("DIAMOND_SWORD"), ItemGroup.SWORD);
        assertEquals(ItemGroup.classify("netherite_axe"), ItemGroup.AXE);
        assertEquals(ItemGroup.classify("CROSSBOW"), ItemGroup.CROSSBOW);
        assertEquals(ItemGroup.classify("IRON_HELMET"), ItemGroup.HELMET);
        assertEquals(ItemGroup.classify("ELYTRA"), ItemGroup.CHESTPLATE);
        assertEquals(ItemGroup.classify("LEATHER_BOOTS"), ItemGroup.BOOTS);
        assertEquals(ItemGroup.classify("STONE_PICKAXE"), ItemGroup.PICKAXE);
        assertNull(ItemGroup.classify("APPLE"));
        assertNull(ItemGroup.classify(null));
    }

    @Test
    void resolveByIdCaseInsensitive() {
        assertNotNull(EnchantRegistry.byId("phoenix"));
        assertNotNull(EnchantRegistry.byId(" PhOeNiX "));
        assertNull(EnchantRegistry.byId("nosuch"));
        assertEquals("phoenix", EnchantRegistry.byId("phoenix").id());
    }
}
