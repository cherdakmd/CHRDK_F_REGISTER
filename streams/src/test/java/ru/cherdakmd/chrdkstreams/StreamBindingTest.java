package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мультиканальная привязка: Twitch и VK Play Live живут вместе, канал одной
 * площадки заменяется, а не добавляется вторым, прогресс остаётся общим.
 */
class StreamBindingTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static StreamBinding.Channel channel(StreamPlatform platform, String name,
                                                 String code, long verifiedAt) {
        return new StreamBinding.Channel(platform, name, code, verifiedAt);
    }

    private static StreamBinding binding(StreamBinding.Channel... channels) {
        return new StreamBinding(PLAYER, "Steve", Arrays.asList(channels),
                new StreamRewards.State(0, 1, 30_000L, 4));
    }

    @Test
    void twoPlatformsCoexist() {
        StreamBinding binding = binding(
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 1L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L));

        assertEquals(2, binding.channels().size());
        assertTrue(binding.linked());
        assertTrue(binding.verified(), "достаточно подтверждения одной площадки");
        assertEquals(StreamPlatform.TWITCH, binding.platform());
        assertEquals("chrdk", binding.channel());
    }

    @Test
    void samePlatformChannelIsReplaced() {
        StreamBinding binding = binding(channel(StreamPlatform.TWITCH, "old", "CHRDK-AAAA", 0L))
                .withChannel(channel(StreamPlatform.TWITCH, "new", "CHRDK-BBBB", 0L));

        assertEquals(1, binding.channels().size(), "на площадке всегда один канал");
        assertEquals("new", binding.channel());
    }

    @Test
    void addingSecondPlatformKeepsBothAndState() {
        StreamBinding original = binding(channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L));
        StreamBinding updated = original.withChannel(
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L));

        assertEquals(2, updated.channels().size());
        assertEquals(original.state(), updated.state(), "прогресс общий, а не на канал");
        assertEquals(4, updated.state().totalHours(), "второй канал не обнуляет счётчики");
        assertEquals(1, updated.state().awardedToday());
    }

    @Test
    void duplicatesCollapseOnConstruction() {
        StreamBinding binding = binding(
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L),
                channel(StreamPlatform.TWITCH, "CHRDK", "CHRDK-BBBB", 0L),
                channel(StreamPlatform.TWITCH, "", "CHRDK-CCCC", 0L),
                null);

        assertEquals(1, binding.channels().size(), "один канал на площадку, пустые отбрасываются");
        assertEquals("CHRDK-AAAA", binding.code());
    }

    @Test
    void withoutRemovesOnlyOnePlatform() {
        StreamBinding binding = binding(
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L));

        StreamBinding updated = binding.without(StreamPlatform.VKPLAY);

        assertEquals(1, updated.channels().size());
        assertEquals(StreamPlatform.TWITCH, updated.platform());
        assertSame(binding.state(), updated.state(), "прогресс переживает отвязку канала");
    }

    @Test
    void primaryPrefersVerifiedChannel() {
        StreamBinding binding = binding(
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L),
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 5L));

        assertEquals(StreamPlatform.TWITCH, binding.platform());
        assertEquals(5L, binding.verifiedAt());
        assertEquals(1, binding.verifiedChannels().size());
    }

    @Test
    void markVerifiedTouchesOnlyTargetChannel() {
        StreamBinding binding = binding(
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L));

        StreamBinding updated = binding.markVerified(StreamPlatform.VKPLAY, "CHRDK_VK", 9L);

        assertEquals(9L, updated.find(StreamPlatform.VKPLAY).orElseThrow().verifiedAt());
        assertEquals(0L, updated.find(StreamPlatform.TWITCH).orElseThrow().verifiedAt());
        assertFalse(updated.find(StreamPlatform.TWITCH).orElseThrow().verified());
    }

    @Test
    void labelsListEveryChannel() {
        StreamBinding binding = binding(
                channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L),
                channel(StreamPlatform.VKPLAY, "chrdk_vk", "CHRDK-BBBB", 0L));

        assertEquals("twitch/chrdk, vkplay/chrdk_vk", binding.labels());
    }

    @Test
    void renamedKeepsChannelsAndState() {
        StreamBinding binding = binding(channel(StreamPlatform.TWITCH, "chrdk", "CHRDK-AAAA", 0L));
        StreamBinding renamed = binding.renamed("Alex");

        assertEquals("Alex", renamed.playerName());
        assertEquals(1, renamed.channels().size());
        assertEquals(binding.state(), renamed.state());
    }
}
