package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetNamesTest {

    @Test
    void miniMessageTagsAreRemoved() {
        assertEquals("Барсик", PetNames.sanitize("<red>Барсик</red>", 16));
        assertEquals("Бар сик", PetNames.sanitize("Бар<bold>сик", 16));
    }

    @Test
    void legacyCodesAreRemoved() {
        assertEquals("Барсик", PetNames.sanitize("§aБарсик", 16));
        assertEquals("Барсик", PetNames.sanitize("&6Барсик", 16));
    }

    @Test
    void cyrillicAndAllowedPunctuationSurvive() {
        assertEquals("Том-Кот_2", PetNames.sanitize("Том-Кот_2", 16));
        assertEquals("Джек'с", PetNames.sanitize("Джек'с", 16));
    }

    @Test
    void forbiddenCharactersBecomeSpaces() {
        assertEquals("Бар сик", PetNames.sanitize("Бар@#$сик", 16));
        assertEquals("Волк", PetNames.sanitize("  Волк  ", 16));
    }

    @Test
    void spacesCollapse() {
        assertEquals("Серый Волк", PetNames.sanitize("Серый   \t Волк", 16));
    }

    @Test
    void lengthIsClampedToLimit() {
        assertEquals("Барсик", PetNames.sanitize("Барсик", 6));
        assertEquals("Барсик", PetNames.sanitize("Барсик", 100).substring(0, 6));
        assertEquals(5, PetNames.sanitize("Барсик", 5).length());
    }

    @Test
    void validityChecksLengthAndEmptiness() {
        assertTrue(PetNames.valid("Барсик", 2, 16));
        assertFalse(PetNames.valid("А", 2, 16));
        assertFalse(PetNames.valid("", 1, 16));
        assertFalse(PetNames.valid(null, 1, 16));
        assertFalse(PetNames.valid("Барсик", 2, 3));
    }
}
