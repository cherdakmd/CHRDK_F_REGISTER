package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сводки: топ стримеров по дням и статистика по площадкам. */
class StreamBoardTest {

    private static StreamBinding.Channel channel(StreamPlatform platform, String name,
                                                 long verifiedAt) {
        return new StreamBinding.Channel(platform, name, "CHRDK-AAAA", verifiedAt);
    }

    private static StreamBinding binding(String name, int hours, StreamBinding.Channel... channels) {
        return new StreamBinding(UUID.nameUUIDFromBytes(name.getBytes()), name,
                Arrays.asList(channels),
                new StreamRewards.State(0, 0, 0L, hours));
    }

    @Test
    void topSortsByHoursDescending() {
        List<StreamBinding> bindings = List.of(
                binding("Ann", 3, channel(StreamPlatform.TWITCH, "ann", 1L)),
                binding("Bob", 10, channel(StreamPlatform.VKPLAY, "bob", 1L)),
                binding("Cid", 5, channel(StreamPlatform.TWITCH, "cid", 1L)));

        List<StreamBoard.Row> rows = StreamBoard.top(bindings, 10);

        assertEquals(List.of("Bob", "Cid", "Ann"),
                rows.stream().map(StreamBoard.Row::playerName).toList());
        assertEquals(10, rows.get(0).totalHours());
    }

    @Test
    void topSkipsPlayersWithoutAwards() {
        List<StreamBinding> bindings = List.of(
                binding("Ann", 0, channel(StreamPlatform.TWITCH, "ann", 1L)),
                binding("Bob", 2, channel(StreamPlatform.TWITCH, "bob", 1L)));

        List<StreamBoard.Row> rows = StreamBoard.top(bindings, 10);

        assertEquals(1, rows.size());
        assertEquals("Bob", rows.get(0).playerName());
    }

    @Test
    void topRespectsLimitAndClampsIt() {
        List<StreamBinding> bindings = List.of(
                binding("A", 3, channel(StreamPlatform.TWITCH, "a", 1L)),
                binding("B", 2, channel(StreamPlatform.TWITCH, "b", 1L)),
                binding("C", 1, channel(StreamPlatform.TWITCH, "c", 1L)));

        assertEquals(2, StreamBoard.top(bindings, 2).size());
        assertEquals(3, StreamBoard.top(bindings, 999).size(), "лимит зажат сверху, но строк меньше");
        assertEquals(1, StreamBoard.top(bindings, 0).size(), "лимит не может быть нулевым");
    }

    @Test
    void tiesAreOrderedByNick() {
        List<StreamBinding> bindings = List.of(
                binding("zed", 4, channel(StreamPlatform.TWITCH, "zed", 1L)),
                binding("Ann", 4, channel(StreamPlatform.TWITCH, "ann", 1L)));

        assertEquals(List.of("Ann", "zed"),
                StreamBoard.top(bindings, 10).stream()
                        .map(StreamBoard.Row::playerName).toList());
    }

    @Test
    void rowKeepsChannelCount() {
        StreamBinding both = binding("Ann", 7,
                channel(StreamPlatform.TWITCH, "ann", 1L),
                channel(StreamPlatform.VKPLAY, "ann_vk", 1L));

        assertEquals(2, StreamBoard.top(List.of(both), 10).get(0).channels());
    }

    @Test
    void emptyInputGivesEmptyBoard() {
        assertTrue(StreamBoard.top(List.of(), 10).isEmpty());
        assertTrue(StreamBoard.top(null, 10).isEmpty());
        assertTrue(StreamBoard.platforms(null).isEmpty());
    }

    @Test
    void platformStatsCountChannelsAndVerified() {
        List<StreamBinding> bindings = List.of(
                binding("Ann", 7,
                        channel(StreamPlatform.TWITCH, "ann", 1L),
                        channel(StreamPlatform.VKPLAY, "ann_vk", 0L)),
                binding("Bob", 1, channel(StreamPlatform.TWITCH, "bob", 0L)));

        List<StreamBoard.PlatformRow> rows = StreamBoard.platforms(bindings);

        assertEquals(2, rows.size());
        StreamBoard.PlatformRow twitch = rows.get(0);
        assertEquals(StreamPlatform.TWITCH, twitch.platform());
        assertEquals(2, twitch.channels());
        assertEquals(1, twitch.verified());
        StreamBoard.PlatformRow vkplay = rows.get(1);
        assertEquals(1, vkplay.channels());
        assertEquals(0, vkplay.verified());
    }

    @Test
    void channelCountSumsEveryBinding() {
        List<StreamBinding> bindings = List.of(
                binding("Ann", 7,
                        channel(StreamPlatform.TWITCH, "ann", 1L),
                        channel(StreamPlatform.VKPLAY, "ann_vk", 1L)),
                binding("Bob", 1, channel(StreamPlatform.TWITCH, "bob", 1L)));

        assertEquals(3, StreamBoard.channelCount(bindings));
        assertEquals(0, StreamBoard.channelCount(List.of()));
    }
}
