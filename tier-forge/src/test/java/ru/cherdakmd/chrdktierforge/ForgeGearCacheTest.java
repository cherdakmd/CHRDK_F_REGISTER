package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Кэш присутствия ковки для пульса (1.4.91). */
class ForgeGearCacheTest {

    private static final long TTL = 30_000L;

    @Test
    void unknownPlayerNeedsScan() {
        ForgeGearCache cache = new ForgeGearCache(TTL);
        assertNull(cache.fresh(UUID.randomUUID(), 1_000L));
        assertEquals(0, cache.size());
    }

    @Test
    void falseAnswerLivesUntilTtl() {
        ForgeGearCache cache = new ForgeGearCache(TTL);
        UUID player = UUID.randomUUID();
        cache.put(player, false, 1_000L);

        assertEquals(Boolean.FALSE, cache.fresh(player, 1_000L));
        assertEquals(Boolean.FALSE, cache.fresh(player, 30_999L), "на границе срока ещё свежо");
        assertNull(cache.fresh(player, 31_000L), "срок истёк — нужен новый скан");
    }

    @Test
    void trueAnswerIsAlsoCachedAndInvalidationWins() {
        ForgeGearCache cache = new ForgeGearCache(TTL);
        UUID player = UUID.randomUUID();
        cache.put(player, true, 5_000L);
        assertEquals(Boolean.TRUE, cache.fresh(player, 10_000L));

        cache.invalidate(player);
        assertNull(cache.fresh(player, 10_000L), "изменение инвентаря снимает кэш сразу");
        assertEquals(0, cache.size());
    }

    @Test
    void ttlIsClampedAndClearDropsEverything() {
        ForgeGearCache cache = new ForgeGearCache(0L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cache.put(first, false, 100L);
        cache.put(second, true, 100L);
        assertEquals(2, cache.size());

        assertNull(cache.fresh(first, 101L), "нулевой TTL не превращается в вечный кэш");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.fresh(second, 100L));
    }
}
