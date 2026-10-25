package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinedBlocksTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void placedBlockIsUsedOnlyOnce() {
        MinedBlocks blocks = new MinedBlocks(60_000L, 100);
        blocks.record(PLAYER, "world", 1, 64, 3, 1_000L);
        assertTrue(blocks.consume(PLAYER, "world", 1, 64, 3, 1_500L));
        assertFalse(blocks.consume(PLAYER, "world", 1, 64, 3, 1_600L),
                "второй раз блок уже не считается поставленным");
        assertEquals(0, blocks.size());
    }

    @Test
    void otherPlayersAndWorldsDoNotMatch() {
        MinedBlocks blocks = new MinedBlocks(60_000L, 100);
        blocks.record(PLAYER, "world", 1, 64, 3, 1_000L);
        assertFalse(blocks.consume(OTHER, "world", 1, 64, 3, 1_100L));
        assertFalse(blocks.consume(PLAYER, "world_nether", 1, 64, 3, 1_100L));
        assertFalse(blocks.consume(PLAYER, "world", 2, 64, 3, 1_100L));
        assertTrue(blocks.consume(PLAYER, "world", 1, 64, 3, 1_200L));
    }

    @Test
    void expiredEntriesAreNotConsumed() {
        MinedBlocks blocks = new MinedBlocks(1_000L, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 1_000L);
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 2_500L));
        assertEquals(0, blocks.size(), "просроченная запись удалена при обращении");
    }

    @Test
    void zeroAmountOfTimeIsNotAccepted() {
        MinedBlocks blocks = new MinedBlocks(0L, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 1_000L);
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 2_000L),
                "TTL не может быть нулевым");
    }

    @Test
    void sizeIsCappedAndOldestEntriesGoFirst() {
        MinedBlocks blocks = new MinedBlocks(60_000L, 64);
        for (int index = 0; index < 200; index++) {
            blocks.record(PLAYER, "world", index, 0, 0, 1_000L + index);
        }
        assertTrue(blocks.size() <= 64, "память выросла выше лимита: " + blocks.size());
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 2_000L),
                "самая старая запись вытеснена");
        assertTrue(blocks.consume(PLAYER, "world", 199, 0, 0, 2_000L));
    }

    @Test
    void clearForgetsEverything() {
        MinedBlocks blocks = new MinedBlocks(60_000L, 100);
        blocks.record(PLAYER, "world", 0, 0, 0, 1_000L);
        blocks.clear();
        assertEquals(0, blocks.size());
        assertFalse(blocks.consume(PLAYER, "world", 0, 0, 0, 1_100L));
    }
}
