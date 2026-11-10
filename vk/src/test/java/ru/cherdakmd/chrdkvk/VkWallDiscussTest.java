package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc.9: подпись «обсудить — ссылка» добавляется только к тексту стены,
 * но не к PNG-сообщениям беседы и не к {@code /vkadmin testcard}.
 */
final class VkWallDiscussTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void stockConfigHasTheExactDiscussSignature() {
        VkConfig cfg = VkConfig.parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile()));
        assertEquals("text", cfg.wallPostMode());
        assertTrue(cfg.textOnlyWall());
        assertEquals("https://vk.me/join/IAYORcZm0ZVtjPdoDazBHP5yymulHst4fxM=",
                VkConfig.DEFAULT_DISCUSS_URL);
        assertEquals("обсудить — " + VkConfig.DEFAULT_DISCUSS_URL, cfg.discussLine());
        assertEquals("Итоги дня\n\nобсудить — " + VkConfig.DEFAULT_DISCUSS_URL,
                cfg.withDiscuss("Итоги дня"));
    }

    @Test
    void allFiveWallPostsAndTestPostGetSignatureButChatDoesNot() throws IOException {
        for (String path : new String[]{
                "src/main/java/ru/cherdakmd/chrdkvk/DailyCardsService.java",
                "src/main/java/ru/cherdakmd/chrdkvk/WallDigestService.java"}) {
            String source = read(path);
            assertTrue(source.contains("String wallText = cfg.withDiscuss(baseText);"), path);
            assertTrue(source.contains("publishText(wallText, guid)"), path);
            assertTrue(source.contains("publisher.publish(png, wallText, guid)"), path);
            assertTrue(source.contains("sendChatCard(cfg, png, baseText, guid"), path);
            assertTrue(source.contains("publishChatCard(png, text, guid)"), path);
            assertFalse(source.contains("sendChatCard(cfg, png, wallText"), path);
        }
        String service = read("src/main/java/ru/cherdakmd/chrdkvk/VkService.java");
        int wallStart = service.indexOf("public void publishTestWallPost(");
        int cardStart = service.indexOf("public void publishTestCard(", wallStart);
        assertTrue(wallStart >= 0 && cardStart > wallStart);
        String wallMethod = service.substring(wallStart, cardStart);
        assertTrue(wallMethod.contains("cfg.withDiscuss(text)"));
        assertTrue(wallMethod.contains("publishText(wallText, guid)"));
        assertFalse(wallMethod.contains("publishChatCard"));

        int cardEnd = service.indexOf("private void onMainThread", cardStart);
        String cardMethod = service.substring(cardStart, cardEnd);
        assertTrue(cardMethod.contains("publishChatCard(png, text, guid)"));
        assertFalse(cardMethod.contains("withDiscuss"));
        assertFalse(cardMethod.contains("wallText"));

        String chat = read("src/main/java/ru/cherdakmd/chrdkvk/VkChatPhotoPublisher.java");
        assertFalse(chat.contains("withDiscuss"));
        assertFalse(chat.contains("vk.me/join"));
        String api = read("src/main/java/ru/cherdakmd/chrdkvk/VkApiClient.java");
        assertTrue(api.contains("params.put(\"dont_parse_links\", \"1\")"));
    }
}
