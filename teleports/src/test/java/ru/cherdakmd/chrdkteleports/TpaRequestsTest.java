package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Реестр TPA: лимиты входящих/исходящих, принятие/отказ/отзыв, истечение. */
class TpaRequestsTest {

    private static final long T0 = 1_000_000L;

    @Test
    void createAcceptFlow() {
        TpaRequests requests = new TpaRequests();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(TpaRequests.CreateResult.OK,
                requests.create(a, b, false, T0, 60));
        TpaRequests.Request accepted = requests.accept(b, T0 + 1000);
        assertNotNull(accepted);
        assertEquals(a, accepted.requester());
        assertEquals(b, accepted.target());
        assertNull(requests.accept(b, T0 + 1000), "принятый запрос исчез");
    }

    @Test
    void limits() {
        TpaRequests requests = new TpaRequests();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertEquals(TpaRequests.CreateResult.SELF, requests.create(a, a, false, T0, 60));
        requests.create(a, b, false, T0, 60);
        assertEquals(TpaRequests.CreateResult.OUTGOING_EXISTS,
                requests.create(a, c, false, T0, 60), "один исходящий на игрока");
        assertEquals(TpaRequests.CreateResult.INCOMING_EXISTS,
                requests.create(c, b, true, T0, 60), "один входящий на цель");
    }

    @Test
    void denyAndCancelOutgoing() {
        TpaRequests requests = new TpaRequests();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        requests.create(a, b, false, T0, 60);
        assertNotNull(requests.deny(b, T0 + 1));
        assertEquals(0, requests.size());
        requests.create(a, b, true, T0, 60);
        TpaRequests.Request cancelled = requests.cancelOutgoing(a, T0 + 1);
        assertNotNull(cancelled);
        assertTrue(cancelled.here());
        assertNull(requests.incomingFor(b, T0 + 1));
    }

    @Test
    void expiryReplacesAndSweeps() {
        TpaRequests requests = new TpaRequests();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        requests.create(a, b, false, T0, 5);
        assertNull(requests.accept(b, T0 + 6000), "истёк");
        // истёкшая запись не блокирует новую
        assertEquals(TpaRequests.CreateResult.OK, requests.create(a, b, true, T0 + 6000, 5));
        List<TpaRequests.Request> expired = requests.sweepExpired(T0 + 20_000);
        assertEquals(1, expired.size());
        assertEquals(0, requests.size());
    }
}
