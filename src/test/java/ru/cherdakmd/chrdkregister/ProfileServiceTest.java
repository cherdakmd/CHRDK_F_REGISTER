package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раскладка карточки {@code /profile} (1.4.156): слоты разделов и округление
 * остатка проходки вверх. С 1.4.186 — ещё и кнопка «Оформление ника».
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
    void labelsButtonDoesNotCoverSectionsOrOtherButtons() {
        assertTrue(ProfileService.LABELS_SLOT >= 0 && ProfileService.LABELS_SLOT < ProfileService.SIZE,
                "кнопка вне меню: " + ProfileService.LABELS_SLOT);
        for (int slot : ProfileService.SECTION_SLOTS) {
            assertTrue(slot != ProfileService.LABELS_SLOT, "кнопка поверх раздела: " + slot);
        }
        assertTrue(ProfileService.LABELS_SLOT != ProfileService.TO_CHAT_SLOT);
        assertTrue(ProfileService.LABELS_SLOT != ProfileService.REFRESH_SLOT);
        assertTrue(ProfileService.LABELS_SLOT != ProfileService.CLOSE_SLOT);
    }

    @Test
    void groupInviteDoesNotCoverSectionsOrOtherButtons() {
        int slot = ProfileService.GROUP_INVITE_SLOT;
        assertTrue(slot >= 0 && slot < ProfileService.SIZE);
        for (int section : ProfileService.SECTION_SLOTS) assertTrue(slot != section);
        assertTrue(slot != ProfileService.HEAD_SLOT && slot != ProfileService.LABELS_SLOT
                && slot != ProfileService.TO_CHAT_SLOT && slot != ProfileService.REFRESH_SLOT
                && slot != ProfileService.CLOSE_SLOT);
    }

    @Test
    void friendCommandIsRegisteredWithProfilePermission() {
        var descriptor = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                java.nio.file.Path.of("src/main/resources/plugin.yml").toFile());
        assertTrue(descriptor.isConfigurationSection("commands.friend"));
        assertEquals("chrdkregister.profile", descriptor.getString("commands.friend.permission"));
    }

    @Test
    void friendCommandRoutesEveryArgumentWithoutChangingIt() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"friend"}, ProfileService.friendArgs(new String[0]));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"friend", "accept", "Player"},
                ProfileService.friendArgs(new String[]{"accept", "Player"}));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"friend", "list", "2"},
                ProfileService.friendArgs(new String[]{"list", "2"}));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"friend", ""}, ProfileService.friendArgs(new String[]{""}));
    }

    @Test
    void friendButtonDoesNotCoverOtherSlots() {
        int slot = ProfileService.FRIEND_INVITE_SLOT;
        assertTrue(slot >= 0 && slot < ProfileService.SIZE);
        for (int section : ProfileService.SECTION_SLOTS) assertTrue(slot != section);
        assertTrue(slot != ProfileService.HEAD_SLOT && slot != ProfileService.LABELS_SLOT
                && slot != ProfileService.TO_CHAT_SLOT && slot != ProfileService.REFRESH_SLOT
                && slot != ProfileService.CLOSE_SLOT && slot != ProfileService.GROUP_INVITE_SLOT);
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
