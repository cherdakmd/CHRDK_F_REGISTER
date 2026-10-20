package ru.cherdakmd.chrdkstreams;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * streams-data.json: сохранение, чтение, поиск по каналу, терпимость к битым
 * строкам и миграция старого формата «один канал = плоские поля».
 */
class StreamStoreTest {

    private static final Logger LOGGER = Logger.getLogger("StreamStoreTest");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path folder;

    private StreamStore store() {
        return new StreamStore(folder, LOGGER);
    }

    private static StreamBinding.Channel channel(StreamPlatform platform, String name,
                                                 String code, long verifiedAt) {
        return new StreamBinding.Channel(platform, name, code, verifiedAt);
    }

    private static StreamBinding binding(UUID uuid, String name, StreamRewards.State state,
                                         StreamBinding.Channel... channels) {
        return new StreamBinding(uuid, name, Arrays.asList(channels), state);
    }

    @Test
    void roundTripKeepsBindingAndProgress() {
        StreamStore store = store();
        StreamBinding binding = binding(PLAYER, "Steve",
                new StreamRewards.State(20_833, 2, 45 * 60_000L, 7),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-7K2M", 1_800_000_000_000L));
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
    void roundTripKeepsAllChannels() {
        StreamStore store = store();
        store.put(binding(PLAYER, "Steve", StreamRewards.State.empty(),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 1_700_000_000_000L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L)));
        store.save();

        StreamStore loaded = store();
        loaded.load();

        StreamBinding restored = loaded.get(PLAYER).orElseThrow();
        assertEquals(2, restored.channels().size(), "оба канала должны пережить запись");
        assertEquals("chrdk_vk", restored.find(StreamPlatform.VKPLAY).orElseThrow().channel());
        assertTrue(restored.find(StreamPlatform.TWITCH).orElseThrow().verified());
        assertFalse(restored.find(StreamPlatform.VKPLAY).orElseThrow().verified());
    }

    @Test
    void savedJsonKeepsFlatFieldsForRollback() throws Exception {
        StreamStore store = store();
        store.put(binding(PLAYER, "Steve", StreamRewards.State.empty(),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L)));
        store.save();

        JsonObject entry = JsonParser.parseString(
                        Files.readString(folder.resolve("streams-data.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject();
        JsonArray channels = entry.getAsJsonArray("channels");
        assertEquals(2, channels.size());
        assertEquals("chrdk", entry.get("channel").getAsString(),
                "плоские поля дублируют основной канал для отката на 1.4.120");
        assertEquals("twitch", entry.get("platform").getAsString());
    }

    @Test
    void oldFlatJsonLoadsAsSingleChannel() throws Exception {
        Files.writeString(folder.resolve("streams-data.json"), """
                {"players":[
                  {"uuid":"11111111-2222-3333-4444-555555555555","name":"Steve",
                   "platform":"vkplay","channel":"chrdk_vk","code":"CHRDK-ABCD",
                   "verifiedAt":5,"day":1,"awardedToday":2,"pendingMillis":60000,"totalDays":3}
                ]}
                """, StandardCharsets.UTF_8);

        StreamStore store = store();
        store.load();

        StreamBinding binding = store.get(PLAYER).orElseThrow();
        assertEquals(1, binding.channels().size(), "старая запись = один канал");
        assertEquals(StreamPlatform.VKPLAY, binding.platform());
        assertEquals("chrdk_vk", binding.channel());
        assertEquals(3, binding.state().totalDays());
        assertEquals(2, binding.state().awardedToday());
    }

    @Test
    void channelsArrayWinsOverFlatFields() throws Exception {
        Files.writeString(folder.resolve("streams-data.json"), """
                {"players":[
                  {"uuid":"11111111-2222-3333-4444-555555555555","name":"Steve",
                   "platform":"twitch","channel":"stale","code":"CHRDK-OLD1","verifiedAt":1,
                   "channels":[
                     {"platform":"twitch","channel":"live","code":"CHRDK-AAAA","verifiedAt":2},
                     {"platform":"vkplay","channel":"live_vk","code":"CHRDK-BBBB","verifiedAt":0}
                   ]}
                ]}
                """, StandardCharsets.UTF_8);

        StreamStore store = store();
        store.load();

        StreamBinding binding = store.get(PLAYER).orElseThrow();
        assertEquals(2, binding.channels().size());
        assertEquals("live", binding.channel(), "массив приоритетнее плоских полей");
    }

    @Test
    void emptyChannelsArrayFallsBackToFlatFields() throws Exception {
        Files.writeString(folder.resolve("streams-data.json"), """
                {"players":[
                  {"uuid":"11111111-2222-3333-4444-555555555555","name":"Steve",
                   "platform":"twitch","channel":"chrdk","code":"CHRDK-ABCD","verifiedAt":0,
                   "channels":[]}
                ]}
                """, StandardCharsets.UTF_8);

        StreamStore store = store();
        store.load();

        assertEquals("chrdk", store.get(PLAYER).orElseThrow().channel());
    }

    @Test
    void byChannelIsCaseInsensitiveAndFindsSecondChannel() {
        StreamStore store = store();
        store.put(binding(PLAYER, "Steve", StreamRewards.State.empty(),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-ABCD", 0L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L)));

        assertTrue(store.byChannel(StreamPlatform.TWITCH, "CHRDK").isPresent(),
                "регистр канала не важен");
        assertTrue(store.byChannel(StreamPlatform.VKPLAY, "CHRDK_VK").isPresent(),
                "второй канал тоже должен находиться");
        assertFalse(store.byChannel(StreamPlatform.TWITCH, "chrdk_vk").isPresent(),
                "каналы разных площадок не путаются");
        assertFalse(store.byChannel(StreamPlatform.TWITCH, "other").isPresent());
    }

    @Test
    void removeDeletesBinding() {
        StreamStore store = store();
        store.put(binding(PLAYER, "Steve", StreamRewards.State.empty(),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-ABCD", 0L)));

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
        store.put(binding(PLAYER, "A", new StreamRewards.State(0, 0, 0L, 4),
                channel(StreamPlatform.TWITCH, "a", "CHRDK-AAAA", 0L)));
        store.put(binding(UUID.randomUUID(), "B", new StreamRewards.State(0, 0, 0L, 6),
                channel(StreamPlatform.VKPLAY, "b", "CHRDK-BBBB", 0L)));

        assertEquals(10, store.totalDays());
    }
}
