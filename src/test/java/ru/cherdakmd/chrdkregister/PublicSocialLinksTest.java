package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.PublicSocialLinks.Platform.*;

class PublicSocialLinksTest {
    @TempDir Path folder;
    private final UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test void acceptsOnlyExplicitCanonicalProfileUrls() {
        assertEquals("https://vk.com/example", PublicSocialLinks.validate(VK, "https://www.vk.com/example").orElseThrow());
        assertEquals("https://vk.ru/example", PublicSocialLinks.validate(VK, "https://vk.ru/example").orElseThrow());
        assertEquals("https://vk.ru/example", PublicSocialLinks.validate(VK, "https://www.vk.ru/example").orElseThrow());
        assertTrue(PublicSocialLinks.validate(VK, "https://vk.ru.evil.test/example").isEmpty());
        assertTrue(PublicSocialLinks.validate(VK, "https://evil.ru/example").isEmpty());
        assertEquals("https://example.ru/path?a=1", PublicSocialLinks.validate(OTHER, "https://example.ru/path?a=1").orElseThrow());
        assertTrue(PublicSocialLinks.validate(DISCORD, "https://discord.com/users/123456789012345678").isPresent());
        assertTrue(PublicSocialLinks.validate(TELEGRAM, "https://t.me/example_user").isPresent());
        assertTrue(PublicSocialLinks.validate(YOUTUBE, "https://youtube.com/@example").isPresent());
        assertTrue(PublicSocialLinks.validate(YOUTUBE, "https://youtube.com/channel/UC12345").isPresent());
        assertTrue(PublicSocialLinks.validate(TWITCH, "https://twitch.tv/example").isPresent());
        assertEquals("https://tiktok.com/@example", PublicSocialLinks.validate(TIKTOK, "https://www.tiktok.com/@example").orElseThrow());
        assertEquals("https://example.com/path?a=1", PublicSocialLinks.validate(OTHER, "https://example.com/path?a=1").orElseThrow());
        for (String bad : new String[]{"http://example.com/a", "https://localhost/a", "https://127.0.0.1/a", "https://example.local/a", "https://user@example.com/a", "https://example.com:443/a", "https://example.com/a#frag"})
            assertTrue(PublicSocialLinks.validate(OTHER, bad).isEmpty(), bad);
        assertTrue(PublicSocialLinks.validate(TIKTOK, "https://tiktok.com.evil.test/@example").isEmpty());
        for (String unsafe : new String[]{"http://vk.com/example", "https://vk.com.evil.test/example",
                "https://vk.com@evil.test/example", "https://evil.test@vk.com/example",
                "https://vk.com:443/example", "https://vk.com/example?redirect=evil",
                "https://vk.com/example#x", "https://vk.com/%65xample", " https://vk.com/example",
                "https://vk.com/example/other", "https://vk.com/<red>"}) {
            assertTrue(PublicSocialLinks.validate(VK, unsafe).isEmpty(), unsafe);
        }
        assertTrue(PublicSocialLinks.validate(DISCORD, "https://discord.gg/invite").isEmpty());
        assertTrue(PublicSocialLinks.validate(TWITCH, "https://vk.com/example").isEmpty());
    }

    @Test void explicitLinksPersistAreIsolatedAndCanBeRemoved() {
        var store = new PublicSocialLinks(folder, Logger.getAnonymousLogger());
        UUID other = UUID.randomUUID();
        assertEquals(Map.of(), store.read(id).orElseThrow());
        assertTrue(store.update(id, VK, "https://vk.ru/example"));
        assertTrue(store.update(id, TELEGRAM, "https://t.me/example_user"));
        assertTrue(store.update(id, TIKTOK, "https://tiktok.com/@example"));
        assertTrue(store.update(id, OTHER, "https://example.com/my-page"));
        assertFalse(store.update(id, VK, "https://evil.test/example"));
        var restarted = new PublicSocialLinks(folder, Logger.getAnonymousLogger());
        assertEquals("https://vk.ru/example", restarted.read(id).orElseThrow().get(VK));
        assertEquals(Map.of(), restarted.read(other).orElseThrow());
        assertTrue(restarted.update(id, VK, null));
        assertEquals(Map.of(TELEGRAM, "https://t.me/example_user", TIKTOK, "https://tiktok.com/@example", OTHER, "https://example.com/my-page"), store.read(id).orElseThrow());
    }

    @Test void failedWriteDoesNotReportSuccess() throws Exception {
        Files.createDirectories(folder.resolve("players"));
        Files.writeString(folder.resolve("players/public-social"), "not a directory");
        var store = new PublicSocialLinks(folder, Logger.getAnonymousLogger());
        assertFalse(store.update(id, VK, "https://vk.com/example"));
    }

    @Test void corruptOrUnsafeFileIsNeverPublishedOrOverwritten() throws Exception {
        var store = new PublicSocialLinks(folder, Logger.getAnonymousLogger());
        Path file = folder.resolve("players/public-social/" + id + ".json");
        Files.createDirectories(file.getParent());
        for (String content : new String[]{"not json", "{\"version\":2,\"links\":{}}",
                "{\"version\":1,\"links\":{\"vk\":\"https://evil.test/user\"}}",
                "{\"version\":1,\"links\":{\"unknown\":\"https://vk.com/user\"}}"}) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            assertTrue(store.read(id).isEmpty());
            assertFalse(store.update(id, VK, "https://vk.com/user"));
            assertEquals(content, Files.readString(file));
        }
    }
}
