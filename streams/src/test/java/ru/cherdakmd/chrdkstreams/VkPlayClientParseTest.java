package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор ответа VK Play Live и построение адреса запроса. */
class VkPlayClientParseTest {

    @Test
    void parsesLiveStream() {
        String body = """
                {"isOnline":true,"isEnded":false,"title":"CHRDK-7K2M | стрим",
                 "count":{"viewers":987,"likes":12,"views":4000},
                 "category":{"title":"Minecraft"},"user":{"nick":"chrdk"}}
                """;

        LiveStream live = VkPlayClient.parseStream(body);

        assertTrue(live.live());
        assertEquals("CHRDK-7K2M | стрим", live.title());
        assertEquals(987, live.viewers());
    }

    @Test
    void offlineChannelIsOffline() {
        String body = """
                {"isOnline":false,"title":"Прошлый эфир","count":{"viewers":0}}
                """;

        assertFalse(VkPlayClient.parseStream(body).live());
    }

    @Test
    void endedStreamIsNotCounted() {
        String body = """
                {"isOnline":true,"isEnded":true,"title":"CHRDK-7K2M","count":{"viewers":0}}
                """;

        assertFalse(VkPlayClient.parseStream(body).live(),
                "запись завершённого эфира не должна давать награду");
    }

    @Test
    void blogNotFoundIsOffline() {
        String body = "{\"error_description\":\"Blog not found\",\"error\":\"blog_not_found\"}";

        assertFalse(VkPlayClient.parseStream(body).live());
    }

    @Test
    void brokenBodyIsOfflineInsteadOfException() {
        assertFalse(VkPlayClient.parseStream(null).live());
        assertFalse(VkPlayClient.parseStream("").live());
        assertFalse(VkPlayClient.parseStream("не json").live());
        assertFalse(VkPlayClient.parseStream("[]").live());
        assertFalse(VkPlayClient.parseStream("{\"isOnline\":\"не bool\"}").live());
    }

    @Test
    void missingViewersMeansZero() {
        String body = "{\"isOnline\":true,\"isEnded\":false,\"title\":\"эфир\"}";

        LiveStream live = VkPlayClient.parseStream(body);
        assertTrue(live.live());
        assertEquals(0, live.viewers());
    }

    @Test
    void urlSubstitutesAndEncodesChannel() {
        VkPlayClient client = new VkPlayClient(HttpClient.newHttpClient(),
                "https://api.vkplay.live/v1/blog/%channel%/public_video_stream");

        assertEquals("https://api.vkplay.live/v1/blog/chrdk/public_video_stream",
                client.urlFor("chrdk"));
        assertEquals("https://api.vkplay.live/v1/blog/a%2Bb/public_video_stream",
                client.urlFor("a+b"));
    }

    @Test
    void blankApiUrlFallsBackToDefault() {
        VkPlayClient client = new VkPlayClient(HttpClient.newHttpClient(), "  ");

        assertEquals("https://api.vkplay.live/v1/blog/chrdk/public_video_stream",
                client.urlFor("chrdk"));
    }
}
