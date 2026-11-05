package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SocialLinkStoreTest {

    private static SocialLinkStore store(Path dir) {
        SocialLinkStore store = new SocialLinkStore(dir, Logger.getLogger("social-test"));
        store.load();
        return store;
    }

    @Test
    void linkIsUniquePerPlayerAndPerProfile(@TempDir Path dir) {
        SocialLinkStore store = store(dir);
        assertEquals(SocialLinkStore.LinkStatus.LINKED,
                store.link("StaveLime", "max", 555L, "555", 1, 100L));
        assertEquals(SocialLinkStore.LinkStatus.PLAYER_ALREADY_LINKED,
                store.link("stavelime", "vk", 999L, "999", 1, 200L));
        assertEquals(SocialLinkStore.LinkStatus.PROFILE_LIMIT_REACHED,
                store.link("Other", "max", 555L, "555", 1, 300L));
        assertEquals(SocialLinkStore.LinkStatus.INVALID_ARGUMENT,
                store.link("Other", "telegram", 555L, "", 1, 300L));
        assertTrue(store.isLinked("STAVELIME"));
        assertEquals("max", store.binding("StaveLime").orElseThrow().provider());
        assertEquals(1, store.linkCount());
    }

    @Test
    void firstSeenIsRecordedOnceAndDonorAccessSurvivesReload(@TempDir Path dir) {
        SocialLinkStore first = store(dir);
        assertEquals(0L, first.firstSeenAt("StaveLime"));
        first.recordFirstSeen("StaveLime", 1_000L);
        first.recordFirstSeen("stavelime", 9_000L);
        first.grantDonor("StaveLime", "manual:оплата на сайте", 2_000L);
        assertTrue(first.donorGranted("STAVELIME"));
        assertTrue(first.donorSource("StaveLime").contains("manual"));
        assertEquals(1, first.donorCount());

        SocialLinkStore second = store(dir);
        assertFalse(second.loadFailed());
        assertEquals(1_000L, second.firstSeenAt("StaveLime"),
                "повторный вход не продлевает личный переходный срок");
        assertTrue(second.donorGranted("StaveLime"));
        assertEquals(java.util.List.of("stavelime"), second.donors());
        assertTrue(second.revokeDonor("STAVELIME"));
        assertFalse(second.revokeDonor("StaveLime"));
        assertEquals(0, second.donorCount());
    }

    @Test
    void survivesReloadWithChecksum(@TempDir Path dir) {
        SocialLinkStore first = store(dir);
        first.link("Player", "vk", 123L, "id123", 1, 100L);
        first.setLastNewsTag("v1.4.287");
        first.setChatId("bridge", 72000001L);
        first.markRewarded("Player", 200L);

        SocialLinkStore second = store(dir);
        assertFalse(second.loadFailed());
        assertEquals("vk", second.binding("player").orElseThrow().provider());
        assertEquals("v1.4.287", second.lastNewsTag());
        assertEquals(72000001L, second.chatId("bridge"));
        assertTrue(second.rewardClaimed("Player"));
    }

    @Test
    void tamperedFileIsPreservedAndFlagged(@TempDir Path dir) throws Exception {
        SocialLinkStore first = store(dir);
        first.link("Player", "max", 1L, "1", 1, 100L);
        Path file = dir.resolve("social-links.json");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, raw.replace("\"provider\": \"max\"", "\"provider\": \"vk\""),
                StandardCharsets.UTF_8);

        SocialLinkStore second = store(dir);
        assertTrue(second.loadFailed());
        assertTrue(Files.isRegularFile(dir.resolve("social-links.json.broken")));
        assertEquals(0, second.linkCount(), "подделанные привязки не загружаются");

        // До подтверждения администратором файл не перезаписывается.
        second.link("Other", "vk", 2L, "2", 1, 200L);
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("\"Other\""));
        second.acknowledgeCorruption(999_999L);
        assertFalse(second.loadFailed());
        assertEquals(999_999L, second.emergencyUntil());
    }

    @Test
    void unlinkReleasesProfileAndEmergencyIsPersisted(@TempDir Path dir) {
        SocialLinkStore store = store(dir);
        store.link("Player", "max", 42L, "42", 1, 100L);
        store.setEmergencyUntil(5_000L);
        assertTrue(store.emergencyUntil() > 0L);
        assertTrue(store.unlink("player").isPresent());
        assertFalse(store.unlink("player").isPresent());
        assertFalse(store.ownerOf("max", 42L).isPresent());
        assertEquals(SocialLinkStore.LinkStatus.LINKED,
                store.link("New", "max", 42L, "42", 1, 200L));
        assertTrue(store(dir).discoveredChats().isEmpty());
    }
}
