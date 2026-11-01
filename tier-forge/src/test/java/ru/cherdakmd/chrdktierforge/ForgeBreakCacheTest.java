package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForgeBreakCacheTest {
    @Test
    void sourceIsIsolatedByPlayerWorldAndEveryCoordinate() {
        ForgeBreakCache<String> cache = new ForgeBreakCache<>(8);
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        var key = new ForgeBreakCache.Key(player, world, 1, -10, 3);
        cache.put(key, "сломанная кирка, не новая рука");
        assertEquals("сломанная кирка, не новая рука", cache.get(key));
        assertNull(cache.get(new ForgeBreakCache.Key(UUID.randomUUID(), world, 1, -10, 3)));
        assertNull(cache.get(new ForgeBreakCache.Key(player, UUID.randomUUID(), 1, -10, 3)));
        assertNull(cache.get(new ForgeBreakCache.Key(player, world, 2, -10, 3)));
        assertNull(cache.get(new ForgeBreakCache.Key(player, world, 1, -9, 3)));
        assertNull(cache.get(new ForgeBreakCache.Key(player, world, 1, -10, 4)));
    }

    @Test
    void boundedCacheKeepsNewestBreakAndClearAlsoRemovesNoDropAndVetoedSnapshots() {
        ForgeBreakCache<Integer> cache = new ForgeBreakCache<>(2);
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        var a = new ForgeBreakCache.Key(player, world, 1, 2, 3);
        var b = new ForgeBreakCache.Key(player, world, 2, 2, 3);
        var c = new ForgeBreakCache.Key(player, world, 3, 2, 3);
        cache.put(a, 1);
        cache.put(b, 2);
        cache.put(a, 3);
        cache.put(c, 4);
        assertEquals(3, cache.get(a));
        assertNull(cache.get(b));
        assertEquals(4, cache.get(c));
        // Даже если новая обычная кирка не создаёт снимка, прошлый уже снят.
        assertEquals(3, cache.remove(a));
        assertNull(cache.get(a));
        assertNull(cache.remove(a));
        cache.put(a, 5);
        cache.clear();
        assertNull(cache.get(a));
        assertNull(cache.get(c));
        assertThrows(IllegalArgumentException.class, () -> new ForgeBreakCache<>(0));
    }
}
