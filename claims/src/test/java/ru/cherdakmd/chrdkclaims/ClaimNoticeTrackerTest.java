package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Память границ приватов: вход/выход и антиспам. Без Bukkit. */
class ClaimNoticeTrackerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ClaimNotice.Spot spot(String owner, int x, int z) {
        return new ClaimNotice.Spot("world", x, z, owner, 0, null, 0L, "");
    }

    @Test
    void firstUpdateSendsOnlyEnter() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        List<ClaimNoticeTracker.Notice> notices =
                tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).entering());
        assertEquals("Steve", notices.get(0).spot().owner());
        assertEquals(1, tracker.tracked());
    }

    @Test
    void sameChunkSendsNothing() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        assertTrue(tracker.update(PLAYER, 1_000L, spot("Steve", 0, 0), 5_000L).isEmpty());
        // смена владельца в том же чанке — всё ещё тот же чанк
        assertTrue(tracker.update(PLAYER, 2_000L, spot("Alex", 0, 0), 5_000L).isEmpty());
    }

    @Test
    void crossingBorderSendsLeaveAndEnter() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        List<ClaimNoticeTracker.Notice> notices =
                tracker.update(PLAYER, 10_000L, spot("Alex", 1, 0), 5_000L);
        assertEquals(2, notices.size());
        assertFalse(notices.get(0).entering());
        assertEquals("Steve", notices.get(0).spot().owner());
        assertTrue(notices.get(1).entering());
        assertEquals("Alex", notices.get(1).spot().owner());
    }

    @Test
    void standingInsideKeepsSpotFreshForLeave() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L,
                new ClaimNotice.Spot("world", 0, 0, "Steve", 0, null, 0L, ""), 0L);
        // Пока игрок стоял в чанке, владелец выставил цену и приветствие.
        tracker.update(PLAYER, 1_000L,
                new ClaimNotice.Spot("world", 0, 0, "Steve", 32, null, 0L,
                        "Добро пожаловать"), 0L);
        List<ClaimNoticeTracker.Notice> leave = tracker.update(PLAYER, 2_000L, null, 0L);
        assertEquals(1, leave.size());
        assertEquals(32, leave.get(0).spot().salePrice());
        assertEquals("Добро пожаловать", leave.get(0).spot().greeting());
    }

    @Test
    void nextChunkOfSameOwnerIsSilent() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 0L);
        assertTrue(tracker.update(PLAYER, 1_000L, spot("Steve", 1, 0), 0L).isEmpty(),
                "соседний чанк того же владельца — та же территория");
        assertTrue(tracker.update(PLAYER, 2_000L, spot("steve", 1, 1), 0L).isEmpty(),
                "регистр ника не делает владельца другим");
        // «Выход» называет чанк, из которого игрок действительно ушёл.
        List<ClaimNoticeTracker.Notice> leave = tracker.update(PLAYER, 3_000L, null, 0L);
        assertEquals(1, leave.size());
        assertEquals(1, leave.get(0).spot().x());
        assertEquals(1, leave.get(0).spot().z());
    }

    @Test
    void otherWorldOrOwnerStillNotifies() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 0L);
        List<ClaimNoticeTracker.Notice> crossed =
                tracker.update(PLAYER, 1_000L, spot("Alex", 1, 0), 0L);
        assertEquals(2, crossed.size(), "другой владелец — выход и вход");
        assertFalse(crossed.get(0).entering());
        assertTrue(crossed.get(1).entering());

        ClaimNotice.Spot nether =
                new ClaimNotice.Spot("world_nether", 1, 0, "Alex", 0, null, 0L, "");
        List<ClaimNoticeTracker.Notice> worlds = tracker.update(PLAYER, 2_000L, nether, 0L);
        assertEquals(2, worlds.size(), "другой мир — тоже граница");
        assertEquals("world", worlds.get(0).spot().world());
    }

    @Test
    void leavingClaimSendsLeaveOnly() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        List<ClaimNoticeTracker.Notice> notices =
                tracker.update(PLAYER, 10_000L, null, 5_000L);
        assertEquals(1, notices.size());
        assertFalse(notices.get(0).entering());
        assertEquals(0, tracker.tracked(), "вне привата состояние чанка не хранится");
    }

    @Test
    void cooldownSuppressesFastBounceButKeepsLaterNotice() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        assertTrue(tracker.update(PLAYER, 100L, null, 5_000L).isEmpty(),
                "выход сразу после входа подавляется");
        assertTrue(tracker.update(PLAYER, 200L, spot("Steve", 0, 0), 5_000L).isEmpty(),
                "повторный вход в тот же чанк в пределах кулдауна подавляется");
        List<ClaimNoticeTracker.Notice> later =
                tracker.update(PLAYER, 6_000L, null, 5_000L);
        assertEquals(1, later.size());
        assertFalse(later.get(0).entering(), "после кулдауна выход снова виден");
    }

    @Test
    void cooldownIsPerChunkNotGlobal() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        List<ClaimNoticeTracker.Notice> notices =
                tracker.update(PLAYER, 1_000L, spot("Alex", 1, 0), 5_000L);
        assertEquals(1, notices.size(),
                "выход из Steve подавлен кулдауном, но вход к Alex не блокируется");
        assertTrue(notices.get(0).entering());
        assertEquals("Alex", notices.get(0).spot().owner());
    }

    @Test
    void forgetAvoidsLeaveAfterTeleportButKeepsCooldown() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        tracker.forget(PLAYER);
        assertTrue(tracker.update(PLAYER, 1_000L, null, 5_000L).isEmpty());
        assertTrue(tracker.update(PLAYER, 2_000L, spot("Steve", 0, 0), 5_000L).isEmpty(),
                "кулдаун уведомления о том же чанке сохраняется после телепорта");
    }

    @Test
    void clearDropsLastChunkAndCooldown() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        tracker.update(PLAYER, 0L, spot("Steve", 0, 0), 5_000L);
        tracker.clear(PLAYER);
        assertEquals(0, tracker.tracked());
        List<ClaimNoticeTracker.Notice> notices =
                tracker.update(PLAYER, 1_000L, spot("Steve", 0, 0), 5_000L);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).entering(), "после выхода с сервера история не мешает");
    }

    @Test
    void nullInputsAreIgnored() {
        ClaimNoticeTracker tracker = new ClaimNoticeTracker();
        assertTrue(tracker.update(null, 0L, spot("Steve", 0, 0), 5_000L).isEmpty());
        tracker.update(PLAYER, 0L, null, 5_000L);
        tracker.forget(null);
        tracker.clear(null);
        assertEquals(0, tracker.tracked());
    }
}
