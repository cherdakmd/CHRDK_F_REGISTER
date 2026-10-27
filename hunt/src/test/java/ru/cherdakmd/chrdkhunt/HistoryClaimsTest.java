package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HistoryClaimsTest {
    private static final class Death { boolean cancelled; }
    @Test void duplicateLiveEventAndRepeatedFinishCannotCreateTwoRecords() {
        var claims = new HistoryClaims<Death>(e -> e.cancelled); UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        Death event = new Death(); assertTrue(claims.begin(id, owner, event));
        assertFalse(claims.begin(id, owner, event)); assertFalse(claims.begin(id, owner, new Death()));
        assertTrue(claims.finish(id, event)); assertFalse(claims.finish(id, event)); assertEquals(0, claims.size());
    }
    @Test void lateCancellationAllowsAnotherRealDeathInTheSameTick() {
        var claims = new HistoryClaims<Death>(e -> e.cancelled); UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        Death cancelled = new Death(), real = new Death(); assertTrue(claims.begin(id, owner, cancelled)); cancelled.cancelled = true;
        assertTrue(claims.begin(id, owner, real)); assertFalse(claims.finish(id, cancelled));
        claims.discard(id, cancelled); assertEquals(1, claims.size()); assertTrue(claims.finish(id, real));
    }
    @Test void cancelledFinalEventDoesNotConsumeFutureHistory() {
        var claims = new HistoryClaims<Death>(e -> e.cancelled); UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        Death event = new Death(); claims.begin(id, owner, event); event.cancelled = true;
        assertFalse(claims.finish(id, event)); assertEquals(0, claims.size());
        Death later = new Death(); assertTrue(claims.begin(id, owner, later)); assertTrue(claims.finish(id, later));
    }
    @Test void pendingBoundAndOwnerCleanupDoNotTouchOtherPlayers() {
        var claims = new HistoryClaims<Death>(e -> e.cancelled); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        UUID id = new UUID(0, 1); Death old = new Death(); claims.begin(id, first, old);
        for (int i = 2; i <= HistoryClaims.LIMIT; i++) assertTrue(claims.begin(new UUID(0, i), second, new Death()));
        assertFalse(claims.begin(UUID.randomUUID(), second, new Death()));
        old.cancelled = true; Death replacement = new Death(); assertTrue(claims.begin(id, first, replacement));
        assertEquals(List.of(id), claims.forget(first)); assertFalse(claims.finish(id, replacement));
        assertEquals(HistoryClaims.LIMIT - 1, claims.size());
        claims.clear(); assertEquals(0, claims.size());
    }
    @Test void queueReplacementCannotRunCancelledOrStaleTicket() {
        List<Runnable> scheduled = new ArrayList<>(); AtomicInteger cancelled = new AtomicInteger(), effects = new AtomicInteger();
        var queue = new MobTaskQueue(action -> { scheduled.add(action); return cancelled::incrementAndGet; });
        UUID id = UUID.randomUUID(); assertTrue(queue.submit(id, "history-death", () -> effects.addAndGet(100)));
        queue.cancel(id, "history-death"); assertEquals(1, cancelled.get());
        assertTrue(queue.submit(id, "history-death", effects::incrementAndGet));
        scheduled.getFirst().run(); assertEquals(1, queue.size()); assertEquals(0, effects.get());
        scheduled.getLast().run(); assertEquals(0, queue.size()); assertEquals(1, effects.get());
        queue.stop(); assertFalse(queue.submit(id, "history-death", effects::incrementAndGet));
    }
    @Test void schedulingFailureReleasesQueueTicket() {
        var queue = new MobTaskQueue(action -> { throw new IllegalStateException(); }); UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> queue.submit(id, "history-death", () -> {}));
        assertEquals(0, queue.size());
    }
    @Test void runtimeChecksFinalDeathDirectDamageWorldPdcAndLifecycleWithoutRewards() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntHistoryTracker.java"));
        assertTrue(source.contains("EntityDeathEvent::isCancelled")); assertTrue(source.contains("!mob.isDead()"));
        assertTrue(source.contains("damage.isCancelled()")); assertTrue(source.contains("mob.getLastDamageCause() != damage"));
        assertTrue(source.contains("if (!killer.equals(attacker)) return"));
        assertTrue(source.contains("view.equals(plugin.progression().view(mob))"));
        assertTrue(source.contains("!killer.getWorld().getUID().equals(world)"));
        assertTrue(source.contains("mob.getPersistentDataContainer().has(claimed)"));
        assertTrue(source.indexOf("recordHistory(owner") < source.indexOf("set(claimed, PersistentDataType.BYTE"));
        for (String forbidden : List.of("registerKill(", "addCoins(", "addShards(", "getDrops(", "sendMessage(", "broadcast(", "setDroppedExp("))
            assertFalse(source.contains(forbidden), forbidden);
        String plugin = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntPlugin.java"));
        assertTrue(plugin.contains("historyTracker.clear()")); assertTrue(plugin.contains("historyTracker.stop()"));
        String command = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/HuntCommandHandler.java"));
        assertTrue(command.contains("historyTracker().forget(target.id())"));
        assertTrue(source.contains("forget(event.getPlayer().getUniqueId())"));
    }
}
