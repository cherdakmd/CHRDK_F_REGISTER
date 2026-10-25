package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Антинакрутка «поставь-сломай»: запись живёт TTL, срабатывает один раз и не
 * пухнет бесконечно.
 *
 * <p>Запись удаляется при первом {@code consume}, поэтому повторная проверка
 * того же блока всегда {@code false} — это ожидаемое поведение, а не баг:
 * второй раз игрок ломает уже настоящий, не поставленный им блок.</p>
 */
class PlacedBlocksTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long TTL = 5_000L;

    @Test
    void blockPlacedByPlayerIsConsumedOnce() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "world", 10, 64, -3, 1_000L);
        assertTrue(blocks.consume(PLAYER, "world", 10, 64, -3, 1_500L));
        assertFalse(blocks.consume(PLAYER, "world", 10, 64, -3, 1_500L),
                "запись одноразовая");
    }

    @Test
    void expiredRecordsAreNotConsumed() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 0L);
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, TTL + 1));
        assertEquals(0, blocks.size(), "просроченная запись удалена при проверке");
    }

    @Test
    void otherPlayerAndWorldDoNotCount() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "world", 1, 2, 3, 100L);
        assertFalse(blocks.consume(OTHER, "world", 1, 2, 3, 200L));
        assertFalse(blocks.consume(PLAYER, "world_nether", 1, 2, 3, 200L));
        assertFalse(blocks.consume(PLAYER, "world", 1, 2, 4, 200L));
        assertTrue(blocks.consume(PLAYER, "world", 1, 2, 3, 200L), "своя запись цела");
    }

    @Test
    void worldNameIsCaseInsensitive() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "World", 5, 5, 5, 0L);
        assertTrue(blocks.consume(PLAYER, "world", 5, 5, 5, 10L));
    }

    @Test
    void sizeIsCappedAndOldestDropped() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 64);
        for (int index = 0; index < 100; index++) {
            blocks.record(PLAYER, "world", index, 0, 0, index);
        }
        assertEquals(64, blocks.size());
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 200L), "самая старая запись вытеснена");
        assertTrue(blocks.consume(PLAYER, "world", 99, 0, 0, 200L));
    }

    @Test
    void pruneRemovesOnlyExpired() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 0L);
        blocks.record(PLAYER, "world", 1, 0, 0, 4_000L);
        blocks.prune(TTL + 1);
        assertEquals(1, blocks.size());
    }

    @Test
    void clearEmptiesEverything() {
        PlacedBlocks blocks = new PlacedBlocks(TTL, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 0L);
        blocks.record(OTHER, "world", 0, 0, 0, 0L);
        assertEquals(2, blocks.size());
        blocks.clear();
        assertEquals(0, blocks.size());
    }

    @Test
    void ttlAndLimitAreClamped() {
        PlacedBlocks blocks = new PlacedBlocks(-1L, 1);
        blocks.record(PLAYER, "world", 0, 0, 0, 0L);
        // Минимальный лимит — 64 записи, минимальный TTL — 1 мс.
        for (int index = 0; index < 70; index++) {
            blocks.record(PLAYER, "world", index, 0, 0, 0L);
        }
        assertEquals(64, blocks.size());
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 2L));
    }
}
