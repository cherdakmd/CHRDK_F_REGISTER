package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ввод канала: стример может вставить ник, ссылку или {@code @ник} — плагин
 * достаёт из этого имя канала, а служебные разделы сайтов отбрасывает.
 */
class StreamPlatformLinkTest {

    @Test
    void plainNickStaysAsIs() {
        assertEquals("chrdk", StreamPlatform.extractChannel("chrdk"));
        assertEquals("CHRDK_Test", StreamPlatform.extractChannel("  CHRDK_Test  "));
    }

    @Test
    void atPrefixIsDropped() {
        assertEquals("chrdk", StreamPlatform.extractChannel("@chrdk"));
    }

    @Test
    void twitchUrlGivesNick() {
        assertEquals("chrdk", StreamPlatform.extractChannel("https://www.twitch.tv/chrdk"));
        assertEquals("chrdk", StreamPlatform.extractChannel("http://twitch.tv/chrdk/"));
        assertEquals("chrdk", StreamPlatform.extractChannel("twitch.tv/chrdk"));
    }

    @Test
    void vkPlayUrlGivesNick() {
        assertEquals("chrdk_vk",
                StreamPlatform.extractChannel("https://vkplay.live/chrdk_vk"));
        assertEquals("chrdk_vk",
                StreamPlatform.extractChannel("https://live.vkvideo.ru/chrdk_vk?ysclid=abc"));
    }

    @Test
    void queryAndFragmentAreIgnored() {
        assertEquals("chrdk", StreamPlatform.extractChannel("https://twitch.tv/chrdk?sr=a"));
        assertEquals("chrdk", StreamPlatform.extractChannel("https://twitch.tv/chrdk#about"));
    }

    @Test
    void trailingSectionsAreIgnored() {
        assertEquals("chrdk", StreamPlatform.extractChannel("https://www.twitch.tv/chrdk/videos"));
        assertEquals("chrdk", StreamPlatform.extractChannel("https://vkplay.live/chrdk/about"));
    }

    @Test
    void reservedSectionsAreNotChannels() {
        assertEquals("", StreamPlatform.extractChannel("https://twitch.tv/directory"));
        assertEquals("", StreamPlatform.extractChannel("https://twitch.tv/videos"));
        assertEquals("", StreamPlatform.extractChannel("https://vkplay.live/settings"));
    }

    @Test
    void emptyInputGivesEmptyChannel() {
        assertEquals("", StreamPlatform.extractChannel(""));
        assertEquals("", StreamPlatform.extractChannel("   "));
        assertEquals("", StreamPlatform.extractChannel(null));
        assertEquals("", StreamPlatform.extractChannel("https://twitch.tv/"));
    }

    @Test
    void extractedChannelPassesValidation() {
        String channel = StreamPlatform.extractChannel("https://www.twitch.tv/chrdk");
        assertTrue(StreamPlatform.validChannel(channel));
    }
}
