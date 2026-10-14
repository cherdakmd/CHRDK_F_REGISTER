package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт сравнения версий для UpdateChecker: нормализация тегов GitHub Releases
 * («v1.4.66», суффиксы сборок) и почленное числовое сравнение.
 */
class UpdateCheckerTest {

    @Test
    void normalizeStripsPrefixAndSuffix() {
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("v1.4.66"));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("V1.4.66"));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("  v1.4.66  "));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("1.4.66"));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("v1.4.66-rc1"));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("v1.4.66+build5"));
        assertEquals("1.4.66", UpdateChecker.normalizeVersion("v1.4.66."));
    }

    @Test
    void normalizeRejectsGarbage() {
        assertEquals("", UpdateChecker.normalizeVersion(null));
        assertEquals("", UpdateChecker.normalizeVersion(""));
        assertEquals("", UpdateChecker.normalizeVersion("   "));
        assertEquals("", UpdateChecker.normalizeVersion("latest"));
    }

    @Test
    void compareDetectsNewerVersions() {
        assertTrue(UpdateChecker.compareVersions("1.4.66", "1.4.65") > 0, "новее патч");
        assertTrue(UpdateChecker.compareVersions("1.5.0", "1.4.65") > 0, "новее минор");
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.9.99") > 0, "новее мажор");
        assertTrue(UpdateChecker.compareVersions("v1.4.66", "1.4.65") > 0, "тег с префиксом v");
    }

    @Test
    void compareDetectsEqualAndOlder() {
        assertEquals(0, UpdateChecker.compareVersions("1.4.65", "1.4.65"));
        assertEquals(0, UpdateChecker.compareVersions("v1.4.65", "1.4.65"), "префикс v роли не играет");
        assertEquals(0, UpdateChecker.compareVersions("1.4.65.0", "1.4.65"), "хвостовые нули не значат");
        assertTrue(UpdateChecker.compareVersions("1.4.65", "1.4.66") < 0, "старее патч");
        assertTrue(UpdateChecker.compareVersions("1.4.65", "1.5.0") < 0, "старее минор");
    }

    @Test
    void compareHandlesDifferentLengthsAndGarbage() {
        assertTrue(UpdateChecker.compareVersions("1.4.1", "1.4") > 0, "длиннее = новее");
        assertTrue(UpdateChecker.compareVersions("1.4", "1.4.1") < 0);
        assertEquals(0, UpdateChecker.compareVersions("1.x.65", "1.0.65"), "битый компонент считается нулём");
        assertTrue(UpdateChecker.compareVersions("1.4.66", "latest") > 0, "мусор трактуется как 0");
    }
}
