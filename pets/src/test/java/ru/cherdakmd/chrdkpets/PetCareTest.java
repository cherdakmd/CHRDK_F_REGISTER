package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetCareTest {

    private static final PetCare.Settings S = PetCare.Settings.DEFAULT;

    @Test
    void decayDropsSatietyAndPullsMoodToNeutral() {
        PetCare.State after = PetCare.decayed(new PetCare.State(80, 80), 0L, 3_600_000L, S);
        assertEquals(72, after.satiety());
        assertEquals(75, after.mood());
    }

    @Test
    void moodRisesToNeutralWhenBelow() {
        PetCare.State after = PetCare.decayed(new PetCare.State(40, 10), 0L, 3_600_000L, S);
        assertEquals(15, after.mood());
    }

    @Test
    void decayIsIdempotentForSameMoment() {
        PetCare.State start = new PetCare.State(60, 60);
        assertEquals(start, PetCare.decayed(start, 5_000L, 5_000L, S));
        assertEquals(start, PetCare.decayed(start, 9_000L, 5_000L, S));
    }

    @Test
    void feedingAndPettingClampAtHundred() {
        PetCare.State fed = PetCare.fed(new PetCare.State(90, 95), S);
        assertEquals(100, fed.satiety());
        assertEquals(100, fed.mood());
        PetCare.State patted = PetCare.patted(new PetCare.State(20, 98), S);
        assertEquals(20, patted.satiety());
        assertEquals(100, patted.mood());
    }

    @Test
    void xpMultiplierStaysWithinDocumentedBounds() {
        assertEquals(1.1, PetCare.xpMultiplier(new PetCare.State(100, 100)), 1.0e-6);
        assertEquals(0.72, PetCare.xpMultiplier(new PetCare.State(0, 0)), 1.0e-6);
        assertEquals(1.0, PetCare.xpMultiplier(null), 1.0e-6);
    }

    @Test
    void settingsClampBadValues() {
        PetCare.Settings parsed = new PetCare.Settings(-10, 500, -3, 7, -1, 4,
                -5, 300, 120, -60, 30, 99_999);
        assertEquals(0, parsed.startSatiety());
        assertEquals(100, parsed.feedSatiety());
        assertEquals(0, parsed.feedMood());
        assertEquals(0, parsed.feedXp());
        assertEquals(0, parsed.satietyDecayPerHour());
        assertEquals(100, parsed.moodDecayPerHour());
        assertEquals(100, parsed.moodNeutral());
        assertEquals(0, parsed.feedCooldownSeconds());
        assertEquals(86_400, parsed.healCooldownSeconds());
    }
}
