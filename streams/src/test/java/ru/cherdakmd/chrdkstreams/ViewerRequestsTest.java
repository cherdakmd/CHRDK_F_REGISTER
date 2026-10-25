package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Срок жизни заявки: неподтверждённая истекает, подтверждённая — никогда. */
class ViewerRequestsTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID STREAMER = UUID.randomUUID();

    private static ViewerBinding request(long requestedAt, boolean accepted) {
        return new ViewerBinding(UUID.randomUUID(), "Viewer", STREAMER, "Streamer",
                accepted, requestedAt, 0L);
    }

    @Test
    void freshRequestIsAlive() {
        assertFalse(ViewerRequests.expired(request(NOW - HOUR, false), 72 * HOUR, NOW));
    }

    @Test
    void oldPendingRequestExpires() {
        assertTrue(ViewerRequests.expired(request(NOW - 100 * HOUR, false), 72 * HOUR, NOW));
    }

    @Test
    void acceptedBindingNeverExpires() {
        assertFalse(ViewerRequests.expired(request(NOW - 1_000 * HOUR, true), 1 * HOUR, NOW));
    }

    @Test
    void zeroTtlDisablesExpiry() {
        assertFalse(ViewerRequests.expired(request(NOW - 9_999 * HOUR, false), 0L, NOW));
        assertEquals(0L, ViewerRequests.ttlMillis(0));
        assertEquals(72 * HOUR, ViewerRequests.ttlMillis(72));
    }
}
