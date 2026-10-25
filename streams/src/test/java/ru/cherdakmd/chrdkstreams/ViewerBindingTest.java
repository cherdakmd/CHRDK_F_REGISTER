package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Жизненный цикл заявки зрителя: подтверждение, чек-ин, смена канала. */
class ViewerBindingTest {

    private static final UUID VIEWER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STREAMER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static ViewerBinding pending() {
        return new ViewerBinding(VIEWER, "Steve", STREAMER, "Streamer", false,
                1_000L, 0L);
    }

    @Test
    void acceptDoesNotStartTheClock() {
        ViewerBinding accepted = pending().accept();

        assertTrue(accepted.accepted());
        assertEquals(0L, accepted.lastCheckIn(),
                "подтверждение не является чек-ином: первый код начнёт счёт");
        assertEquals(1_000L, accepted.requestedAt(), "время заявки сохраняется");
        assertFalse(pending().accepted(), "исходная заявка не меняется");
    }

    @Test
    void checkInMovesOnlyTheTimestamp() {
        ViewerBinding binding = pending().accept().checkedIn(65_000L);

        assertEquals(65_000L, binding.lastCheckIn());
        assertEquals(1_000L, binding.requestedAt(), "время заявки не переписывается чек-ином");
        assertTrue(binding.accepted());
        assertEquals("Steve", binding.playerName());
    }

    @Test
    void renameDoesNotResetProgress() {
        ViewerBinding renamed = pending().accept().checkedIn(65_000L).renamed("Alex");

        assertEquals("Alex", renamed.playerName());
        assertEquals(65_000L, renamed.lastCheckIn());
    }

    @Test
    void movingToAnotherStreamerNeedsApprovalAgain() {
        ViewerBinding moved = pending().accept().checkedIn(65_000L)
                .moveTo(UUID.randomUUID(), "Other", false, 70_000L);

        assertFalse(moved.accepted());
        assertEquals(70_000L, moved.requestedAt());
        assertEquals(0L, moved.lastCheckIn(), "старый чек-ин не переносится на новый канал");
        assertEquals("Other", moved.streamerName());
    }

    @Test
    void movingWithoutApprovalStillWaitsForFirstCode() {
        ViewerBinding moved = pending().moveTo(UUID.randomUUID(), "Other", true, 70_000L);

        assertTrue(moved.accepted());
        assertEquals(0L, moved.lastCheckIn(),
                "новый канал — новый счёт: первый код даёт 0 минут");
    }

    @Test
    void namesAreTrimmed() {
        ViewerBinding binding = new ViewerBinding(VIEWER, "  Steve ", STREAMER, " Streamer ",
                false, 0L, 0L);

        assertEquals("Steve", binding.playerName());
        assertEquals("Streamer", binding.streamerName());
        assertEquals("", new ViewerBinding(VIEWER, null, STREAMER, null, false, 0L, 0L).playerName());
    }
}
