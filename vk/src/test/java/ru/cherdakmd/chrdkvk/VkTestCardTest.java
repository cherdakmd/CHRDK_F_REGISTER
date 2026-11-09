package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /vkadmin testcard}: проверяем реальный PNG, маркер «не новость», guid
 * и контракт команды без живой публикации и без api.vk.com.
 */
final class VkTestCardTest {

    private static final UUID SAMPLE =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DAY = "2026-11-03";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static VkConfig stock() {
        return VkConfig.parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile()));
    }

    private static Messages messages() throws IOException {
        String yml = read("src/main/resources/messages.yml");
        return Messages.from(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)),
                null, Logger.getLogger("VkTestCardTest"));
    }

    @Test
    void markerIsTestPostMarkerAndIsFilteredFromAnnouncements() {
        String marker = VkCommandHandler.testCardMarker(SAMPLE);
        assertTrue(marker.startsWith(VkWallPublisher.TEST_POST_MARKER));
        assertTrue(marker.contains("card_"));
        String text = VkCommandHandler.testCardText("", "CONSOLE",
                Instant.parse("2026-11-03T06:00:00Z"), marker);
        VkApiClient.WallPost post =
                new VkApiClient.WallPost(42L, 0L, text, false, false, false);
        assertFalse(VkService.eligibleWallPost(post, stock()),
                "тестовая карточка не должна анонсироваться в игровом чате");
    }

    @Test
    void defaultTextIsTechnicalAndCustomTextKeepsMarker() {
        String marker = VkCommandHandler.testCardMarker(SAMPLE);
        String fallback = VkCommandHandler.testCardText("", "CONSOLE",
                Instant.parse("2026-11-03T06:00:00Z"), marker);
        assertTrue(fallback.contains("PNG-карточки"));
        assertTrue(fallback.contains("CONSOLE"));
        assertTrue(fallback.contains("не игровая новость"));
        assertTrue(fallback.endsWith(marker));
        assertFalse(fallback.contains("UUID"));

        String custom = VkCommandHandler.testCardText("  Мой тест  ", "CONSOLE",
                Instant.now(), marker);
        assertTrue(custom.startsWith("Мой тест\n"));
        assertTrue(custom.endsWith(marker));
    }

    @Test
    void guidDoesNotCollideWithDailyCardsOrDigest() {
        String guid = VkService.testCardGuid(SAMPLE);
        assertTrue(guid.startsWith(VkService.TEST_CARD_GUID_PREFIX));
        assertFalse(guid.contains("chrdk-card-"));
        assertFalse(guid.contains("chrdk-wall-"));
        assertFalse(guid.contains("chrdk-test-"));
    }

    @Test
    void renderProducesRealPngWithoutPlayerData() throws Exception {
        DailyCardsConfig cards = VkService.testCardConfig();
        assertFalse(cards.showValues(), "на тестовой карточке не показываем игровые величины");
        assertTrue(cards.footer().contains("ТЕСТ"));
        byte[] png = VkService.renderTestCard(cards, DAY);
        assertTrue(png.length > 10_000, "карточка не должна быть пустышкой");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(WallDigestCard.WIDTH, image.getWidth());
        assertEquals(WallDigestCard.HEIGHT, image.getHeight());
    }

    @Test
    void commandAndPublisherContractAreWired() throws IOException {
        String handler = read("src/main/java/ru/cherdakmd/chrdkvk/VkCommandHandler.java");
        assertTrue(handler.contains("case \"testcard\" -> handleTestCard(sender, args, messages)"));
        assertTrue(handler.contains("\"testpost\", \"testcard\", \"sync\""));
        assertTrue(handler.contains("service.publishTestCard(text"));
        assertTrue(handler.contains("admin.testcard-queued"));
        assertTrue(handler.contains("admin.testcard-ok"));
        assertTrue(handler.contains("admin.testcard-failed"));

        String service = read("src/main/java/ru/cherdakmd/chrdkvk/VkService.java");
        int start = service.indexOf("public void publishTestCard(");
        int end = service.indexOf("private void onMainThread", start);
        assertTrue(start >= 0 && end > start);
        String method = service.substring(start, end);
        assertTrue(method.contains("VkWallPublisher(client, groupId).publish(png, text, guid)"),
                "должна использоваться полная цепочка photos → wall.post");
        assertTrue(method.contains("renderTestCard(cards"));
        assertTrue(method.contains("VkWallPublisher.TEST_POST_MARKER"));
        assertTrue(method.contains("testCardGuid(UUID.randomUUID())"));
        assertFalse(method.contains("ledger"), "тест не пишет суточные ledger'ы");
        assertFalse(method.contains("audit()"), "тест не пишет социальный аудит");
        assertFalse(method.contains("markPosted"));
        assertFalse(method.contains("lastAnnouncedPostId"));

        String pluginYml = read("src/main/resources/plugin.yml");
        assertTrue(pluginYml.contains("testcard"));
        String messagesYml = read("src/main/resources/messages.yml");
        assertTrue(messagesYml.contains("testcard|sync"));
        Messages messages = messages();
        for (String key : new String[]{
                "admin.testcard-not-ready", "admin.testcard-too-long",
                "admin.testcard-queued", "admin.testcard-ok", "admin.testcard-failed"}) {
            assertTrue(messages.has(key), "нет ключа " + key);
        }
    }

    @Test
    void newMessagesRenderWithoutLeftoverPlaceholders() throws IOException {
        Messages messages = messages();
        assertTrue(messages.has("admin.testcard-not-ready"));
        assertTrue(messages.has("admin.testcard-queued"));
        String tooLong = messages.raw("admin.testcard-too-long", "max", "1000");
        assertFalse(tooLong.contains("%max%"));
        String ok = messages.raw("admin.testcard-ok",
                "url", "https://vk.ru/wall-237779683_42", "post_id", "42");
        assertFalse(ok.contains("%url%"));
        assertFalse(ok.contains("%post_id%"));
        assertTrue(ok.contains("42"));
        String failed = messages.raw("admin.testcard-failed", "error", "VK API error 27");
        assertFalse(failed.contains("%error%"));
        assertTrue(failed.contains("27"));
    }
}
