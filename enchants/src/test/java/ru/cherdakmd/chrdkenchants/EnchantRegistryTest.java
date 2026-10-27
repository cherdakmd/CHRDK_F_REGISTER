package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог 1.4.210: 74 чар — 59 светлых и 15 проклятий, уникальные id,
 * валидные группы поддержки, редкости и смешанная конфликтная группа.
 */
class EnchantRegistryTest {

    @Test
    void seventyFourUniqueEnchants() {
        List<EnchantDef> all = EnchantRegistry.all();
        assertEquals(74, all.size());
        Set<String> ids = new HashSet<>();
        all.forEach(def -> {
            assertTrue(ids.add(def.id()), "дубликат id " + def.id());
            assertEquals(def.id(), def.id().toLowerCase(Locale.ROOT));
            assertTrue(def.maxLevel() >= 1 && def.maxLevel() <= 3);
            assertNotNull(def.supports());
            assertFalse(def.supports().isEmpty());
            assertNotNull(def.polarity(), "у чары " + def.id() + " нет полярности");
        });
    }

    @Test
    void polaritySplitIsFiftyNineAndFifteen() {
        assertEquals(59, EnchantRegistry.lightCount());
        assertEquals(15, EnchantRegistry.curseCount());
        assertEquals(74, EnchantRegistry.lightCount() + EnchantRegistry.curseCount());
        EnchantRegistry.light().forEach(def -> assertFalse(def.curse(), def.id()));
        EnchantRegistry.curses().forEach(def -> assertTrue(def.curse(), def.id()));
    }

    @Test
    void cursesStayCheapButPainful() {
        // Проклятие не должно быть «наградой»: никаких легендарных и эпических.
        for (EnchantDef curse : EnchantRegistry.curses()) {
            assertTrue(curse.rarity() == EnchantDef.Rarity.COMMON
                            || curse.rarity() == EnchantDef.Rarity.RARE,
                    "проклятие " + curse.id() + " слишком редкое для минуса");
            assertTrue(curse.maxLevel() >= 2, "у проклятия должен быть рост уровня");
        }
        long rare = EnchantRegistry.curses().stream()
                .filter(def -> def.rarity() == EnchantDef.Rarity.RARE).count();
        assertEquals(1, rare, "единственное редкое проклятие — «Проклятая кровь»");
    }

    @Test
    void wardGroupMixesLightAndCurse() {
        Set<String> ward = new HashSet<>();
        EnchantRegistry.all().stream()
                .filter(def -> "ward".equals(def.conflictGroup()))
                .forEach(def -> ward.add(def.id()));
        assertEquals(Set.of("bulwark", "second_skin", "thin_padding"), ward);
        assertTrue(EnchantRegistry.byId("bulwark").polarity() == EnchantDef.Polarity.LIGHT);
        assertTrue(EnchantRegistry.byId("thin_padding").curse());
    }

    @Test
    void categoriesAreCovered() {
        Set<ItemGroup> used = new HashSet<>();
        EnchantRegistry.all().forEach(def -> used.addAll(def.supports()));
        for (ItemGroup group : ItemGroup.values()) {
            assertTrue(used.contains(group), "нет чары для " + group);
        }
        // 19 явных мечевых + 4 «на любой предмет» (Книжник и три проклятия).
        assertEquals(23, EnchantRegistry.all().stream().filter(def ->
                def.supports().contains(ItemGroup.SWORD)).count(),
                "меч: 9 старых + 4 светлых + 3 PvE + 3 проклятия + 4 любых");
        // 11 явных кирковых + те же 4 «на любой предмет».
        assertEquals(15, EnchantRegistry.all().stream().filter(def ->
                def.supports().contains(ItemGroup.PICKAXE)).count(),
                "кирка: 8 светлых + 3 проклятия + 4 любых");
    }

    @Test
    void rareDistributionSanity() {
        long legendary = EnchantRegistry.all().stream()
                .filter(def -> def.rarity() == EnchantDef.Rarity.LEGENDARY).count();
        long common = EnchantRegistry.all().stream()
                .filter(def -> def.rarity() == EnchantDef.Rarity.COMMON).count();
        assertTrue(legendary >= 5 && legendary <= 8, "легендарных должно быть горстка");
        assertTrue(common >= 18 && common <= 28, "обычные чары должны оставаться доступны в нижнем пуле");
        assertEquals(4, EnchantRegistry.all().stream()
                .map(EnchantDef::rarity).distinct().count(), "все 4 редкости представлены");
    }

    @Test
    void polarityLabelsAreStable() {
        // Ключи лора и имён книг держатся на этих строках — не переименовывать.
        assertEquals("polarity-light", EnchantDef.Polarity.LIGHT.labelKey());
        assertEquals("polarity-curse", EnchantDef.Polarity.CURSE.labelKey());
        assertFalse(EnchantDef.Polarity.LIGHT.curse());
        assertTrue(EnchantDef.Polarity.CURSE.curse());
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
        assertNotNull(EnchantRegistry.byId("cursed_blood"));
        assertNull(EnchantRegistry.byId("nosuch"));
        assertEquals("phoenix", EnchantRegistry.byId("phoenix").id());
    }
}
