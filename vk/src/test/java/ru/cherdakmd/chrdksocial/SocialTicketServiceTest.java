package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Тикет не содержит приватных полей и не пропускает спам. */
class SocialTicketServiceTest {

    @Test
    void cooldownBlocksRepeatedRequests() {
        SocialTicketService tickets = new SocialTicketService();
        var first = tickets.create("Steve", "нужна помощь", 1_000L, 30_000L);
        assertTrue(first.isPresent());
        assertEquals("Steve", first.get().player());
        assertTrue(tickets.create("Steve", "опять", 2_000L, 30_000L).isEmpty());
        assertTrue(tickets.create("steve", "опять", 2_000L, 30_000L).isEmpty(),
                "регистр ника не обходит кулдаун");
        assertTrue(tickets.create("Steve", "снова", 31_001L, 30_000L).isPresent());
        assertTrue(tickets.create("", "пусто", 40_000L, 30_000L).isEmpty());
        assertEquals(1, tickets.pendingPlayers());
    }

    @Test
    void zeroCooldownAllowsEveryRequest() {
        SocialTicketService tickets = new SocialTicketService();
        assertTrue(tickets.create("Alex", "a", 1L, 0L).isPresent());
        assertTrue(tickets.create("Alex", "b", 1L, 0L).isPresent());
    }
}
