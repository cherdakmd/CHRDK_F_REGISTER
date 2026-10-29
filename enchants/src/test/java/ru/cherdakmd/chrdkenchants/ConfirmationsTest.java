package ru.cherdakmd.chrdkenchants;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConfirmationsTest {
    @Test void wrongCodeDoesNotConsumeButSuccessfulTokenIsSingleUse() {
        var store = new Confirmations<String>(); var player = UUID.randomUUID();
        var q = store.put(player, 100, "plan"); assertNotNull(q);
        assertNull(store.take(player, UUID.randomUUID(), 100)); assertEquals(1, store.size());
        assertEquals("plan", store.take(player, q.code(), 30100)); assertNull(store.take(player, q.code(), 30100));
    }
    @Test void expiredAndClockRollbackConsumeWithoutExecution() {
        var store = new Confirmations<String>(); var player = UUID.randomUUID();
        var q = store.put(player, 100, "plan"); assertNull(store.take(player, q.code(), 30101)); assertEquals(0, store.size());
        q = store.put(player, 100, "plan"); assertNull(store.take(player, q.code(), 99)); assertEquals(0, store.size());
        assertNull(store.put(player, Long.MAX_VALUE, "plan")); assertNull(store.put(player, -1, "plan"));
    }
    @Test void replacementInvalidatesOldCodeAndCapacityIsHardBound() {
        var store = new Confirmations<Integer>();
        for(int i=0;i<Confirmations.LIMIT;i++) assertNotNull(store.put(new UUID(0,i), 10, i));
        assertNull(store.put(UUID.randomUUID(),10,999)); assertEquals(Confirmations.LIMIT,store.size());
        var player = new UUID(0,0); var old = store.put(player,10,500); var fresh = store.put(player,11,501);
        assertNotEquals(old.code(),fresh.code()); assertNull(store.take(player,old.code(),12));
        assertEquals(501,store.take(player,fresh.code(),12));
        assertNotNull(store.put(UUID.randomUUID(), 40000, 9)); assertEquals(1,store.size());
        store.clear(); assertEquals(0,store.size());
    }
}
