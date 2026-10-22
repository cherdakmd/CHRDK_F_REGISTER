package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetNamesTest {

    @Test
    void miniMessageTagsAreRemoved() {
        assertEquals("Барсик", PetNames.sanitize("<red>Барсик</red>", 16));
        assertEquals("Барсик", PetNames.sanitize("<gradient:red:gold>Барсик</gradient>", 16));
    }

    @Test
    void legacyColorCodesAreRemoved() {
        assertEquals("Барсик", PetNames.sanitize("§cБарсик", 16));
        assertEquals("Барсик", PetNames.sanitize("&cБарсик", 16));
    }

    @Test
    void forbiddenCharactersBecomeSpaces() {
        assertEquals("Бар сик", PetNames.sanitize("Бар*сик", 16));
        assertEquals("Барсик", PetNames.sanitize("Бар\u0007сик", 16));
    }

    @Test
    void spacesAreCollapsedAndTrimmed() {
        assertEquals("Барсик", PetNames.sanitize("  Барсик   ", 16));
        assertEquals("Тихий сторож", PetNames.sanitize("Тихий   сторож", 16));
    }

    @Test
    void lengthIsClamped() {
        assertEquals("Бар", PetNames.sanitize("Барсик", 3));
        assertEquals(16, PetNames.sanitize("ОченьДлиннаяКличкаПитомца", 16).length());
    }

    @Test
    void emptyNameWhenNothingIsLeft() {
        assertEquals("", PetNames.sanitize("<red>§c***</red>", 16));
        assertEquals("", PetNames.sanitize(null, 16));
        assertEquals("", PetNames.sanitize("   ", 16));
    }

    @Test
    void validChecksBounds() {
        assertTrue(PetNames.valid("Барсик", 2, 16));
        assertTrue(PetNames.valid("Бо", 2, 16));
        assertFalse(PetNames.valid("Б", 2, 16));
        assertFalse(PetNames.valid("", 1, 16));
        assertFalse(PetNames.valid("ОченьДлиннаяКличка", 2, 16));
    }
}
