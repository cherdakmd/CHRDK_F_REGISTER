package ru.cherdakmd.chrdkbp;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Память о поставленных блоках: срок, однократность, изоляция игроков, лимит. */
class BattlePassRecentPlacesTest {

    @Test
    void placedBlockIsRememberedOnce() {
        BattlePassRecentPlaces places = new BattlePassRecentPlaces(60_000L, 100);
        UUID player = UUID.randomUUID();
        places.record(player, "world", 10, 64, 10, 1_000L);
        assertTrue(places.consume(player, "world", 10, 64, 10, 1_500L));
        assertFalse(places.consume(player, "world", 10, 64, 10, 1_600L),
                "запись удаляется при первом обращении");
    }

    @Test
    void expiredEntriesNoLongerCount() {
        BattlePassRecentPlaces places = new BattlePassRecentPlaces(1_000L, 100);
        UUID player = UUID.randomUUID();
        places.record(player, "world", 0, 0, 0, 1_000L);
        assertFalse(places.consume(player, "world", 0, 0, 0, 2_001L));
        assertEquals(0, places.size());
    }

    @Test
    void otherPlayersAndWorldsAreIsolated() {
        BattlePassRecentPlaces places = new BattlePassRecentPlaces(60_000L, 100);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        places.record(player, "world", 1, 2, 3, 1_000L);
        assertFalse(places.consume(other, "world", 1, 2, 3, 1_000L));
        assertFalse(places.consume(player, "world_nether", 1, 2, 3, 1_000L));
        assertTrue(places.consume(player, "world", 1, 2, 3, 1_000L));
    }

    @Test
    void pruneDropsOldEntriesAndSizeIsCapped() {
        BattlePassRecentPlaces places = new BattlePassRecentPlaces(1_000L, 16);
        UUID player = UUID.randomUUID();
        for (int index = 0; index < 40; index++) {
            places.record(player, "world", index, 64, 0, 1_000L + index);
        }
        assertTrue(places.size() <= 16, "память ограничена: " + places.size());
        places.prune(10_000L);
        assertEquals(0, places.size());
    }
}
