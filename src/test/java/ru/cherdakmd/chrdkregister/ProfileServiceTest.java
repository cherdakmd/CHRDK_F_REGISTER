package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раскладка карточки {@code /profile} (1.4.156): слоты разделов и округление
 * остатка проходки вверх.
 */
class ProfileServiceTest {

    private static final long DAY = 86_400_000L;

    @Test
    void daysLeftRoundsUp() {
        long now = 1_000_000L;
        assertEquals(0L, ProfileService.daysLeft(now, now));
        assertEquals(0L, ProfileService.daysLeft(now - 1L, now));
        assertEquals(1L, ProfileService.daysLeft(now + 1L, now));
        assertEquals(1L, ProfileService.daysLeft(now + DAY, now));
        assertEquals(2L, ProfileService.daysLeft(now + DAY + 1L, now));
        assertEquals(30L, ProfileService.daysLeft(now + 30L * DAY, now));
    }

    @Test
    void sectionSlotsAreInsideTheMenuAndUnique() {
        assertEquals(21, ProfileService.SECTION_SLOTS.length);
        boolean[] seen = new boolean[ProfileService.SIZE];
        for (int slot : ProfileService.SECTION_SLOTS) {
            assertTrue(slot >= 0 && slot < ProfileService.SIZE, "слот вне меню: " + slot);
            assertTrue(!seen[slot], "слот повторяется: " + slot);
            seen[slot] = true;
        }
        assertEquals(-1, ProfileService.sectionIndex(ProfileService.HEAD_SLOT));
        assertEquals(-1, ProfileService.sectionIndex(ProfileService.CLOSE_SLOT));
        assertEquals(0, ProfileService.sectionIndex(ProfileService.SECTION_SLOTS[0]));
        assertEquals(20, ProfileService.sectionIndex(ProfileService.SECTION_SLOTS[20]));
    }
}
