package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Диалог живёт ровно до TTL и не выполняется после истечения. */
class SocialDialogStoreTest {

    @Test
    void stateExpiresAndCanBeCancelled() {
        SocialDialogStore store = new SocialDialogStore();
        store.begin("vk:10", "confirm", "freeze", 5_000L, 1_000L);
        var state = store.peek("vk:10", 2_000L);
        assertNotNull(state);
        assertTrue(state.matches("confirm"));
        assertEquals("freeze", state.arg());
        assertNull(store.peek("vk:10", 6_001L), "просроченное подтверждение не выполняется");
        assertEquals(0, store.size());

        store.begin("vk:10", "confirm", "logout", 5_000L, 10_000L);
        store.end("vk:10");
        assertNull(store.peek("vk:10", 10_001L));
    }

    @Test
    void purgeRemovesOnlyExpiredStates() {
        SocialDialogStore store = new SocialDialogStore();
        store.begin("a", "confirm", "", 100L, 0L);
        store.begin("b", "confirm", "", 10_000L, 0L);
        assertEquals(1, store.purge(500L));
        assertEquals(1, store.size());
    }
}
