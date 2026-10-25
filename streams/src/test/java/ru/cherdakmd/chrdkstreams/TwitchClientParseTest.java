package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор ответов Twitch Helix и OAuth без сети. */
class TwitchClientParseTest {

    @Test
    void parsesLiveStream() {
        String body = """
                {"data":[{"id":"1","user_login":"chrdk","user_name":"CHRDK",
                "title":"CHRDK-7K2M | вечерний стрим","viewer_count":1234,"type":"live"}],
                "pagination":{}}
                """;

        LiveStream live = TwitchClient.parseStreams(body, "chrdk");

        assertTrue(live.live());
        assertEquals("CHRDK-7K2M | вечерний стрим", live.title());
        assertEquals(1234, live.viewers());
    }

    @Test
    void emptyDataMeansOffline() {
        LiveStream live = TwitchClient.parseStreams("{\"data\":[],\"pagination\":{}}", "chrdk");

        assertFalse(live.live());
        assertEquals("", live.title());
        assertEquals(0, live.viewers());
    }

    @Test
    void ignoresStreamOfAnotherChannel() {
        String body = """
                {"data":[{"user_login":"other","title":"чужой эфир","viewer_count":10,"type":"live"}]}
                """;

        assertFalse(TwitchClient.parseStreams(body, "chrdk").live());
    }

    @Test
    void nonLiveTypeIsOffline() {
        String body = """
                {"data":[{"user_login":"chrdk","title":"запись","viewer_count":0,"type":"rerun"}]}
                """;

        assertFalse(TwitchClient.parseStreams(body, "chrdk").live());
    }

    @Test
    void brokenBodyIsOfflineInsteadOfException() {
        assertFalse(TwitchClient.parseStreams(null, "chrdk").live());
        assertFalse(TwitchClient.parseStreams("", "chrdk").live());
        assertFalse(TwitchClient.parseStreams("не json", "chrdk").live());
        assertFalse(TwitchClient.parseStreams("[1,2,3]", "chrdk").live());
        assertFalse(TwitchClient.parseStreams("{\"data\":{}}", "chrdk").live());
    }

    @Test
    void loginComparisonIsCaseInsensitive() {
        String body = """
                {"data":[{"user_login":"CHRDK","title":"эфир","viewer_count":5,"type":"live"}]}
                """;

        assertTrue(TwitchClient.parseStreams(body, "chrdk").live());
    }

    @Test
    void parsesOAuthToken() {
        TwitchClient.TokenData data = TwitchClient.parseToken(
                "{\"access_token\":\"abc123\",\"expires_in\":5011271,\"token_type\":\"bearer\"}");

        assertTrue(data.present());
        assertEquals("abc123", data.token());
        assertEquals(5_011_271, data.expiresInSeconds());
    }

    @Test
    void missingTokenIsEmpty() {
        assertFalse(TwitchClient.parseToken("{}").present());
        assertFalse(TwitchClient.parseToken("{\"access_token\":\"\"}").present());
        assertFalse(TwitchClient.parseToken("не json").present());
        assertFalse(TwitchClient.parseToken(null).present());
    }

    @Test
    void channelIsUrlEncoded() {
        assertEquals("chrdk", TwitchClient.encode("chrdk"));
        assertEquals("a%2Bb", TwitchClient.encode("a+b"));
        assertEquals("", TwitchClient.encode(null));
    }
}
