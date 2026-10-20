package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Кэш опроса: свежие ответы отдаются, устаревшие — нет, TTL 0 выключает кэш. */
class ProbeCacheTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void freshEntryIsReturned() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("эфир", 7), NOW);

        assertTrue(cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW + 1_000L, 60_000L).isPresent());
        assertEquals(7, cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW, 60_000L)
                .orElseThrow().stream().viewers());
    }

    @Test
    void staleEntryIsNotReturned() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("эфир", 7), NOW);

        assertTrue(cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW + 61_000L, 60_000L).isEmpty());
    }

    @Test
    void zeroTtlDisablesCache() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("эфир", 7), NOW);

        assertTrue(cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW, 0L).isEmpty());
        assertFalse(cache.aggregate(PLAYER, NOW, 0L).live());
    }

    @Test
    void platformsOfSamePlayerDoNotMix() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("twitch", 3), NOW);
        cache.put(PLAYER, StreamPlatform.VKPLAY, LiveStream.offline(), NOW);

        assertTrue(cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW, 60_000L).isPresent());
        assertFalse(cache.fresh(PLAYER, StreamPlatform.VKPLAY, NOW, 60_000L)
                .orElseThrow().stream().live());
    }

    @Test
    void aggregateSumsFreshChannels() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("twitch", 3), NOW);
        cache.put(PLAYER, StreamPlatform.VKPLAY, LiveStream.live("vk", 2), NOW + 1_000L);
        cache.put(UUID.randomUUID(), StreamPlatform.VKPLAY, LiveStream.live("чужой", 50), NOW);

        StreamProbe probe = cache.aggregate(PLAYER, NOW + 2_000L, 60_000L);

        assertTrue(probe.live());
        assertEquals(5L, probe.viewers(), "в сумму идут только каналы этого игрока");
    }

    @Test
    void forgetRemovesPlayerEntries() {
        ProbeCache cache = new ProbeCache();
        cache.put(PLAYER, StreamPlatform.TWITCH, LiveStream.live("twitch", 3), NOW);
        cache.put(PLAYER, StreamPlatform.VKPLAY, LiveStream.live("vk", 2), NOW);

        cache.forget(PLAYER);

        assertEquals(0, cache.size());
        assertTrue(cache.fresh(PLAYER, StreamPlatform.TWITCH, NOW, 60_000L).isEmpty());
    }
}
