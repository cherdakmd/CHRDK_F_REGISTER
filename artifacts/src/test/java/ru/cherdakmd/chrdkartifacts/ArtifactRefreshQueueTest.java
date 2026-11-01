package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactRefreshQueueTest {
    @Test
    void manyInventoryEventsBecomeOneRefreshPerPlayer() {
        var queue = new ArtifactRefreshQueue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 0; i < 100; i++) queue.request(first);
        queue.request(second);
        assertEquals(Set.of(first, second), queue.drain());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void quittingOrDeadPlayerIsRemovedButCanRespawnOrRejoin() {
        var queue = new ArtifactRefreshQueue();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        queue.request(player);
        queue.request(other);
        queue.forget(player);
        assertEquals(Set.of(other), queue.drain());
        queue.request(player);
        assertEquals(Set.of(player), queue.drain());
    }

    @Test
    void newEventsAfterDrainBelongToNextBatch() {
        var queue = new ArtifactRefreshQueue();
        UUID player = UUID.randomUUID();
        queue.request(player);
        Set<UUID> previous = queue.drain();
        queue.request(player);
        assertEquals(Set.of(player), previous);
        assertEquals(Set.of(player), queue.drain());
        queue.forget(player);
        queue.forget(player);
        assertTrue(queue.drain().isEmpty());
    }
}
