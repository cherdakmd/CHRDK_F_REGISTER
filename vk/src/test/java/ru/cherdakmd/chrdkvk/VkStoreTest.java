package ru.cherdakmd.chrdkvk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VkStoreTest {

    private static final Logger LOGGER = Logger.getLogger("VkStoreTest");

    @TempDir
    Path tempDir;

    @Test
    void enforcesAccountLimitPersistsLinksAndBlocksDuplicateRewardFarming() {
        VkStore store = new VkStore(tempDir, LOGGER);
        store.load();
        long now = 1_700_000_000_000L;

        assertEquals(VkStore.LinkStatus.LINKED, store.link("Steve", 100500L, 1, now));
        assertEquals(VkStore.LinkStatus.ALREADY_LINKED_PLAYER, store.link("STEVE", 999999L, 1, now));
        assertEquals(VkStore.LinkStatus.VK_ACCOUNT_LIMIT_REACHED, store.link("Alex", 100500L, 1, now));

        assertTrue(store.canClaimLinkBoost("Steve", 100500L));
        store.markLinkBoostClaimed("Steve", 100500L);
        assertFalse(store.canClaimLinkBoost("Steve", 100500L));
        assertFalse(store.canClaimLinkBoost("Alex", 100500L), "тот же vk_user_id не фармит буст на твинке");
        assertFalse(store.canClaimLinkBoost("Steve", 200600L), "тот же игрок не фармит буст сменой ВК");

        store.updateMemberStatus(100500L, true);
        store.updateDonutStatus(100500L, true, now);
        assertTrue(store.advanceLastAnnouncedPostId(42L));
        assertFalse(store.advanceLastAnnouncedPostId(41L));
        assertTrue(store.saveNow());

        VkStore reloaded = new VkStore(tempDir, LOGGER);
        reloaded.load();
        assertEquals(1, reloaded.linkCount());
        assertEquals(42L, reloaded.lastAnnouncedPostId());
        VkStore.LinkSnapshot snap = reloaded.byPlayer("steve").orElseThrow();
        assertEquals(100500L, snap.vkUserId());
        assertTrue(snap.member());
        assertTrue(reloaded.donutPerkActive("Steve", now + TimeUnit.HOURS.toMillis(24), 72));
        assertFalse(reloaded.donutPerkActive("Steve", now + TimeUnit.HOURS.toMillis(80), 72));
        assertFalse(reloaded.canClaimLinkBoost("Steve", 200600L));
    }
}
