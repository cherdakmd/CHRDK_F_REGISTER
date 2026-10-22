package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetXpRulesTest {

    private static final PetXpRules.Factors FACTORS = new PetXpRules.Factors(1.10, 1.25, 1.25);
    private static final PetCare.State FULL = new PetCare.State(100, 100);

    @Test
    void nearBonusApplies() {
        double plain = PetXpRules.multiplier(true, FULL, false, false, false, true, FACTORS);
        double near = PetXpRules.multiplier(true, FULL, true, false, false, true, FACTORS);
        assertEquals(1.1, plain, 1.0e-6);
        assertEquals(1.21, near, 1.0e-6);
    }

    @Test
    void nightBonusRespectsWolfOnlyFlag() {
        double wolf = PetXpRules.multiplier(true, FULL, false, true, false, true, FACTORS);
        double cat = PetXpRules.multiplier(false, FULL, false, true, false, true, FACTORS);
        double catAllowed = PetXpRules.multiplier(false, FULL, false, true, false, false, FACTORS);
        assertEquals(1.375, wolf, 1.0e-6);
        assertEquals(1.1, cat, 1.0e-6);
        assertEquals(1.375, catAllowed, 1.0e-6);
    }

    @Test
    void weekendMultipliesAndTotalIsCapped() {
        double weekend = PetXpRules.multiplier(true, FULL, true, true, true, true, FACTORS);
        assertEquals(1.890625, weekend, 1.0e-6);
        double capped = PetXpRules.multiplier(true, FULL, true, true, true, true,
                new PetXpRules.Factors(3, 3, 3));
        assertTrue(capped <= PetXpRules.MAX_MULTIPLIER);
    }

    @Test
    void hungryPetStillGainsSomething() {
        double hungry = PetXpRules.multiplier(false, new PetCare.State(0, 0),
                false, false, false, true, FACTORS);
        assertTrue(hungry >= 0.7);
        assertTrue(hungry < 1.0);
    }
}
