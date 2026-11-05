package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseAnnouncementTest {

    @Test
    void stableReleaseIsParsed() {
        var release = ReleaseAnnouncement.parseLatest(JsonParser.parseString("""
                {"tag_name":"v1.4.287","name":"Особые звания","draft":false,"prerelease":false,
                 "html_url":"https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/v1.4.287"}
                """).getAsJsonObject());
        assertNotNull(release);
        assertEquals("v1.4.287", release.tag());
        assertTrue(release.stable());
        assertTrue(ReleaseAnnouncement.format("%tag% — %title%: %url%", release)
                .contains("Особые звания"));
    }

    @Test
    void draftsRcsAndEmptyTagsAreIgnored() {
        assertNull(ReleaseAnnouncement.parseLatest(JsonParser.parseString(
                "{\"tag_name\":\"v1.4.288\",\"draft\":true}").getAsJsonObject()));
        assertNull(ReleaseAnnouncement.parseLatest(JsonParser.parseString(
                "{\"tag_name\":\"v1.4.288-rc.1\",\"prerelease\":true}").getAsJsonObject()));
        assertNull(ReleaseAnnouncement.parseLatest(JsonParser.parseString("{}").getAsJsonObject()));
        assertNull(ReleaseAnnouncement.parseLatest(null));
    }

    @Test
    void missingNameFallsBackToTag() {
        var release = ReleaseAnnouncement.parseLatest(JsonParser.parseString(
                "{\"tag_name\":\"v1.4.287\"}").getAsJsonObject());
        assertNotNull(release);
        assertEquals("v1.4.287", release.title());
        assertTrue(release.url().contains("v1.4.287"));
    }
}
