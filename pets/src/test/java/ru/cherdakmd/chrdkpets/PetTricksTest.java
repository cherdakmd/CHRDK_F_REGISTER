package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetTricksTest {

    @Test
    void lookupIsCaseInsensitive() {
        assertEquals(PetTricks.SPIN, PetTricks.byId("SPIN"));
        assertEquals(PetTricks.FETCH, PetTricks.byId(" fetch "));
        assertNull(PetTricks.byId("dance"));
        assertNull(PetTricks.byId(null));
    }

    @Test
    void tricksOpenWithMood() {
        assertTrue(PetTricks.SIT.availableAt(15));
        assertFalse(PetTricks.SIT.availableAt(14));
        assertTrue(PetTricks.JUMP.availableAt(100));
        assertFalse(PetTricks.JUMP.availableAt(69));
    }

    @Test
    void idsAreUniqueAndDeclared() {
        assertEquals(5, PetTricks.values().length);
        assertEquals("sit", PetTricks.SIT.id());
        assertEquals("CRIT", PetTricks.SPIN.effect());
    }

    @Test
    void learnChanceGrowsWithMoodAndCapsAtNinetyFive() {
        assertEquals(0.35, PetTricks.learnChance(0, 0.35, 0.005), 1.0e-9);
        assertEquals(0.60, PetTricks.learnChance(50, 0.35, 0.005), 1.0e-9);
        assertEquals(0.85, PetTricks.learnChance(100, 0.35, 0.005), 1.0e-9);
        assertEquals(0.95, PetTricks.learnChance(100, 0.95, 0.02), 1.0e-9);
        assertEquals(0.35, PetTricks.learnChance(-50, 0.35, 0.005), 1.0e-9);
    }
}
