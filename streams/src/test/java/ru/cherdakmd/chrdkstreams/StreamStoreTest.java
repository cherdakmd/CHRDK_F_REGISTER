package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** streams-data.json: сохранение, чтение, поиск по каналу и терпимость к битым строкам. */
class StreamStoreTest {

    private static final Logger LOGGER = Logger.getLogger("StreamStoreTest");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path folder;

    private StreamStore store() {
        return new StreamStore(folder, LOGGER);
    }

    @Test
    void roundTripKeepsBindingAndProgress() {
        StreamStore store = store();
        StreamBinding binding = new StreamBinding(PLAYER, "Steve", StreamPlatform.TWITCH,
                "chrdk", "CHRDK-7K2M", 1_800_000_000_000L,
                new StreamRewards.State(20_833, 2, 45 * 60_000L, 7));
        store.put(binding);
        store.save();

        StreamStore loaded = store();
        loaded.load();

        StreamBinding restored = loaded.get(PLAYER).orElseThrow();
        assertEquals("Steve", restored.playerName());
        assertEquals(StreamPlatform.TWITCH, restored.platform());
        assertEquals("chrdk", restored.channel());
        assertEquals("CHRDK-7K2M", restored.code());
        assertEquals(1_800_000_000_000L, restored.verifiedAt());
        assertEquals(2, restored.state().awardedToday());
        assertEquals(45 * 60_000L, restored.state().pendingMillis());
        assertEquals(7, restored.state().totalDays());
        assertTrue(restored.verified());
    }

    @Test
    void byChannelIsCaseInsensitive() {
        StreamStore store = store();
        store.put(new StreamBinding(PLAYER, "Steve", StreamPlatform.VKPLAY,
                "CHRDK", "CHRDK-ABCD", 0L, StreamRewards.State.empty()));

        assertTrue(store.byChannel(StreamPlatform.VKPLAY, "chrdk").isPresent());
        assertFalse(store.byChannel(StreamPlatform.TWITCH, "chrdk").isPresent());
        assertFalse(store.byChannel(StreamPlatform.VKPLAY, "other").isPresent());
    }

    @Test
    void removeDeletesBinding() {
        StreamStore store = store();
        store.put(new StreamBinding(PLAYER, "Steve", StreamPlatform.TWITCH,
                "chrdk", "CHRDK-ABCD", 0L, StreamRewards.State.empty()));

        assertTrue(store.remove(PLAYER));
        assertFalse(store.remove(PLAYER));
        assertEquals(0, store.size());
    }

    @Test
    void missingFileLoadsEmptyStore() {
        StreamStore store = store();
        store.load();

        assertEquals(0, store.size());
        assertTrue(store.get(PLAYER).isEmpty());
    }

    @Test
    void brokenEntriesAreSkipped() throws Exception {
        Path file = folder.resolve("streams-data.json");
        Files.writeString(file, """
                {"players":[
                  {"uuid":"not-a-uuid","platform":"twitch","channel":"a"},
                  {"uuid":"11111111-2222-3333-4444-555555555555","platform":"unknown","channel":"b"},
                  {"uuid":"22222222-3333-4444-5555-666666666666","platform":"twitch","channel":""},
                  {"uuid":"33333333-4444-5555-6666-777777777777","platform":"vkplay","channel":"good","code":"CHRDK-ABCD","verifiedAt":5,"totalDays":3}
                ]}
                """, StandardCharsets.UTF_8);

        StreamStore store = store();
        store.load();

        assertEquals(1, store.size(), "уцелеть должна одна корректная запись");
        StreamBinding binding = store.get(
                UUID.fromString("33333333-4444-5555-6666-777777777777")).orElseThrow();
        assertEquals(StreamPlatform.VKPLAY, binding.platform());
        assertEquals(3, binding.state().totalDays());
    }

    @Test
    void totalDaysSumsAwards() {
        StreamStore store = store();
        store.put(new StreamBinding(PLAYER, "A", StreamPlatform.TWITCH, "a", "CHRDK-AAAA", 0L,
                new StreamRewards.State(0, 0, 0L, 4)));
        store.put(new StreamBinding(UUID.randomUUID(), "B", StreamPlatform.VKPLAY, "b",
                "CHRDK-BBBB", 0L, new StreamRewards.State(0, 0, 0L, 6)));

        assertEquals(10, store.totalDays());
    }
}
