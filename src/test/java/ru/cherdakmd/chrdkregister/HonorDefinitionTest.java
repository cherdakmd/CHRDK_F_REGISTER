package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Определение звания: градиент, санитизация id и правка полей. */
class HonorDefinitionTest {

    @Test
    void oldCitrusIsLimeToTurquoiseAndGrantsFifthLevel() {
        HonorDefinition def = HonorDefinition.oldCitrus();
        assertEquals("old-citrus", def.id());
        assertEquals("Старый цитрус", def.name());
        assertEquals(5, def.grantLevel());
        assertTrue(def.format().contains("#32CD32"));
        assertTrue(def.format().contains("#40E0D0"));
        assertTrue(def.format().contains("%name%"));
        assertTrue(def.listed());
        assertEquals("<gradient:#32CD32:#40E0D0>Steve</gradient>", def.decorate("Steve"));
        assertEquals("", def.decorate(null));
    }

    @Test
    void idIsNormalisedAndFormatAlwaysKeepsName() {
        // Кириллица превращается в дефисы и схлопывается: остаётся латинская часть.
        assertEquals("citrus", HonorDefinition.normalizeId("  Старый Citrus!! "));
        assertEquals("old-citrus-2026", HonorDefinition.normalizeId("Old Citrus 2026"));
        assertTrue(HonorDefinition.validId("Old Citrus 2026"));
        assertFalse(HonorDefinition.validId(""));
        assertFalse(HonorDefinition.validId("   "));
        HonorDefinition def = new HonorDefinition("x", "X", "<gold>", "", 9, "", "", true);
        assertEquals(5, def.grantLevel(), "ступень зажата потолком");
        assertEquals("<gold>%name%", def.format(), "шаблон без %name% дополняется");
    }

    @Test
    void editingFieldsNeverBreaksTheDefinition() {
        HonorDefinition def = HonorDefinition.oldCitrus();
        assertEquals(3, def.with("level", "3").grantLevel());
        assertEquals(def, def.with("level", "77"));
        assertEquals(def, def.with("level", "мусор"));
        assertEquals(def, def.with("unknown", "x"));
        assertEquals("Ветеран", def.with("name", "Ветеран").name());
        assertFalse(def.with("listed", "false").listed());
        assertEquals(def, def.with("listed", "возможно"));
        assertEquals("✦", def.with("badge", " ✦ ").badge());
        assertEquals(0, HonorDefinition.parseLevel("0"));
        assertEquals(-1, HonorDefinition.parseLevel("6"));
        assertEquals(-1, HonorDefinition.parseLevel(""));
    }
}
