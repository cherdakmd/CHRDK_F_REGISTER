package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetModeTest {

    @Test
    void idsRoundTripAndUnknownFallsBackToFollow() {
        assertEquals(PetMode.STAY, PetMode.byId("stay"));
        assertEquals(PetMode.FOLLOW, PetMode.byId("FOLLOW"));
        assertEquals(PetMode.FOLLOW, PetMode.byId("dance"));
        assertEquals(PetMode.FOLLOW, PetMode.byId(null));
        assertEquals("stay", PetMode.STAY.id());
    }
}
