package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2: тестовая запись {@code /vkadmin testpost} не становится игровой
 * новостью и не двигает {@code last_announced_post_id}.
 */
final class VkTestPostTest {

    private static VkConfig stock() {
        return VkConfig.parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile()));
    }

    private static VkApiClient.WallPost post(long id, String text) {
        return new VkApiClient.WallPost(id, 0L, text, false, false, false);
    }

    @Test
    void markerExcludesTestPostFromWallAnnouncements() {
        VkConfig cfg = stock();
        assertFalse(VkService.eligibleWallPost(post(10L,
                "🧪 Тест CHRDK_VK\n" + VkWallPublisher.TEST_POST_MARKER + "abcd1234"), cfg),
                "Тест не должен анонсироваться в игровом чате");
        assertTrue(VkService.eligibleWallPost(post(11L, "Итоги дня\n#chrdk_41e8aac7211ccfa5"), cfg),
                "Обычная запись дайджеста по-прежнему анонсируется");
        assertTrue(VkService.eligibleWallPost(post(12L, "Новость релиза"), cfg));
    }

    @Test
    void markerIsDistinctFromDailyDigestAndCardGuids() {
        assertTrue(VkWallPublisher.TEST_POST_MARKER.startsWith("#chrdk_test_"));
        assertFalse(VkWallPublisher.TEST_POST_MARKER.equals("#chrdk_"));
        assertFalse("chrdk-wall-2026-11-03".contains(VkWallPublisher.TEST_POST_MARKER));
        assertFalse("chrdk-card-richest-2026-11-03".contains(VkWallPublisher.TEST_POST_MARKER));
    }
}
