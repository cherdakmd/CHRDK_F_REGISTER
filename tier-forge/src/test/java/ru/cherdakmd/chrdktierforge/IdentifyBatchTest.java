package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Сводка пачки опознаний (1.4.94). */
class IdentifyBatchTest {

    private static TierDef.Snapshot tier(String id) {
        return new TierDef.Snapshot(id, 1, TierDef.indexOf(id));
    }

    @Test
    void emptyAndNullRollsGiveEmptyBatch() {
        assertTrue(IdentifyBatch.of(null).isEmpty());
        assertTrue(IdentifyBatch.of(List.of()).isEmpty());
        assertTrue(IdentifyBatch.of(Arrays.asList(null, null)).isEmpty(), "null-роллы не считаются");
        assertNull(IdentifyBatch.of(List.of()).best());
        assertFalse(IdentifyBatch.of(List.of()).allCursed());
    }

    @Test
    void bestTierWinsAndCountIncludesEverything() {
        IdentifyBatch batch = IdentifyBatch.of(List.of(
                tier("common"), tier("rare"), tier("fine"), tier("legendary")));
        assertEquals(4, batch.total());
        assertEquals("legendary", batch.best().id());
        assertEquals(0, batch.cursed());
        assertFalse(batch.allCursed());
    }

    @Test
    void cursedIsCountedAndOnlyAllCursedFlagsTheMessage() {
        IdentifyBatch mixed = IdentifyBatch.of(List.of(tier("cursed"), tier("common")));
        assertEquals(2, mixed.total());
        assertEquals(1, mixed.cursed());
        assertFalse(mixed.allCursed(), "среди находок есть обычный тир");
        assertEquals("common", mixed.best().id());

        IdentifyBatch all = IdentifyBatch.of(List.of(tier("cursed"), tier("cursed")));
        assertTrue(all.allCursed());
        assertEquals("cursed", all.best().id());
    }

    @Test
    void nullEntriesDoNotBreakTheSummary() {
        IdentifyBatch batch = IdentifyBatch.of(Arrays.asList(null, tier("epic"), null, tier("weak")));
        assertEquals(2, batch.total());
        assertEquals("epic", batch.best().id(), "пустые роллы не участвуют в выборе лучшего");
    }
}
