package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило мультиканального опроса: эфир идёт по любому живому каналу, зрители
 * суммируются, порог {@code min-viewers} сравнивается с суммой.
 */
class StreamProbeTest {

    @Test
    void emptyProbeIsOffline() {
        assertFalse(StreamProbe.OFFLINE.live());
        assertEquals(0L, StreamProbe.OFFLINE.viewers());
        assertFalse(StreamProbe.OFFLINE.passes(0));
    }

    @Test
    void liveChannelCountsViewers() {
        StreamProbe probe = StreamProbe.OFFLINE.plus(LiveStream.live("эфир", 7));

        assertTrue(probe.live());
        assertEquals(7L, probe.viewers());
    }

    @Test
    void viewersAreSummedAcrossChannels() {
        StreamProbe probe = StreamProbe.OFFLINE
                .plus(LiveStream.live("twitch", 3))
                .plus(LiveStream.live("vkplay", 2));

        assertEquals(5L, probe.viewers(), "порог 5 проходят два эфира по 3 и 2 зрителя");
        assertTrue(probe.passes(5));
        assertFalse(probe.passes(6));
    }

    @Test
    void offlineChannelsDoNotAddViewers() {
        StreamProbe probe = StreamProbe.OFFLINE
                .plus(LiveStream.live("twitch", 4))
                .plus(LiveStream.offline());

        assertTrue(probe.live());
        assertEquals(4L, probe.viewers());
    }

    @Test
    void nullAndNegativeViewersAreSafe() {
        StreamProbe probe = StreamProbe.OFFLINE
                .plus(null)
                .plus(new LiveStream(true, "", -5));

        assertTrue(probe.live());
        assertEquals(0L, probe.viewers());
        assertTrue(probe.passes(0));
    }
}
