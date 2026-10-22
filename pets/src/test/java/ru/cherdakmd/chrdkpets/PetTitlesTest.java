package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetTitlesTest {

    @Test
    void titlesGrowWithLevel() {
        assertEquals(PetTitles.BABY, PetTitles.of(1));
        assertEquals(PetTitles.BABY, PetTitles.of(2));
        assertEquals(PetTitles.FRIEND, PetTitles.of(3));
        assertEquals(PetTitles.LOYAL, PetTitles.of(5));
        assertEquals(PetTitles.GUARDIAN, PetTitles.of(7));
        assertEquals(PetTitles.LEGEND, PetTitles.of(9));
        assertEquals(PetTitles.LEGEND, PetTitles.of(10));
        assertEquals(PetTitles.BABY, PetTitles.of(0));
    }

    @Test
    void everyTitleHasMessageKey() {
        for (PetTitles title : PetTitles.values()) {
            assertEquals("title-", title.messageKey().substring(0, 6));
        }
    }
}
