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
 * Разделение токенов: community key остаётся на wall.post, пользовательский
 * ключ администратора — только на photos.*. Живого api.vk.com нет.
 */
final class VkPhotoTokenTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void configSeparatesCommunityAndPhotoTokens() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("community.group-id", 237779683L);
        yaml.set("community.access-token", "vk1.a.community");
        VkConfig cfg = VkConfig.parse(yaml);
        assertEquals("vk1.a.community", cfg.accessToken());
        assertEquals("", cfg.photoAccessToken());
        assertTrue(cfg.apiConfigured());
        assertFalse(cfg.photoPublishConfigured());

        yaml.set("community.photo-user-token", "  vk1.a.admin  ");
        cfg = VkConfig.parse(yaml);
        assertEquals("vk1.a.admin", cfg.photoAccessToken());
        assertEquals("vk1.a.community", cfg.accessToken());
        assertTrue(cfg.photoPublishConfigured());
    }

    @Test
    void publisherRoutesPhotosToSeparateClientAndWallPostToCommunity() throws IOException {
        String publisher = read("src/main/java/ru/cherdakmd/chrdkvk/VkWallPublisher.java");
        assertTrue(publisher.contains("photoApi.call(\"photos.getWallUploadServer\""));
        assertTrue(publisher.contains("photoApi.call(\"photos.saveWallPhoto\""));
        assertTrue(publisher.contains("photoApi.postMultipart("));
        assertTrue(publisher.contains("api.call(\"wall.post\", params)"));
        assertTrue(publisher.contains("this.photoApi = photoApi == null ? api : photoApi"));
    }

    @Test
    void pluginCreatesPhotoClientWithoutLoggingTheToken() throws IOException {
        String plugin = read("src/main/java/ru/cherdakmd/chrdkvk/VkPlugin.java");
        assertTrue(plugin.contains("public VkApiClient photoApiClient()"));
        assertTrue(plugin.contains("cfg.photoAccessToken()"), "фото-клиент не должен подставлять community token");
        assertTrue(plugin.contains("new VkApiClient(cfg.apiBaseUrl(), cfg.photoAccessToken(), cfg.apiVersion()"));
        assertFalse(plugin.contains("access_token="),
                "значение ключа не должно попадать в логи плагина");
        assertTrue(plugin.contains("community.photo-user-token не задан"));
    }

    @Test
    void scheduledPostersRequirePhotoTokenAndUseBothClients() throws IOException {
        for (String path : new String[]{
                "src/main/java/ru/cherdakmd/chrdkvk/DailyCardsService.java",
                "src/main/java/ru/cherdakmd/chrdkvk/WallDigestService.java"}) {
            String source = read(path);
            assertTrue(source.contains("plugin.communityApiClient()"), path);
            assertTrue(source.contains("plugin.photoApiClient()"), path);
            assertTrue(source.contains("photoPublishConfigured()"), path);
            assertTrue(source.contains("VK API error 27"), path);
        }
        String handler = read("src/main/java/ru/cherdakmd/chrdkvk/VkCommandHandler.java");
        int handlerStart = handler.indexOf("private void handleTestCard(");
        assertTrue(handlerStart >= 0);
        String handleMethod = handler.substring(handlerStart, handlerStart + 1200);
        assertTrue(handleMethod.contains("photoPublishConfigured()"),
                "/vkadmin testcard не должен уходить в очередь без фото-ключа");

        String service = read("src/main/java/ru/cherdakmd/chrdkvk/VkService.java");
        int start = service.indexOf("public void publishTestCard(");
        int end = service.indexOf("private void onMainThread", start);
        assertTrue(start >= 0 && end > start);
        String method = service.substring(start, end);
        assertTrue(method.contains("cfg.photoPublishConfigured()"));
        assertTrue(method.contains("plugin.photoApiClient()"));
        assertFalse(method.contains("new VkWallPublisher(client, groupId)"));
    }

    @Test
    void textOnlyTestPostStaysOnCommunityTokenWithoutPhotoKey() throws IOException {
        String service = read("src/main/java/ru/cherdakmd/chrdkvk/VkService.java");
        int start = service.indexOf("public void publishTestWallPost(");
        int end = service.indexOf("public void publishTestCard(", start);
        assertTrue(start >= 0 && end > start);
        String method = service.substring(start, end);
        assertTrue(method.contains("cfg.apiConfigured()"));
        assertFalse(method.contains("photoPublishConfigured()"),
                "текстовый wall.post не должен требовать пользовательский ключ");
        assertTrue(method.contains("new VkWallPublisher(client, groupId).publishText(text, guid)"));
    }

    @Test
    void stockConfigDocumentsTheNewKeyWithoutSecrets() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("photo-user-token: \"\""));
        assertTrue(config.contains("VK API error 27"));
        assertTrue(config.contains("wall,photos,offline"));
        assertTrue(config.contains("НЕ Callback secret"));
        assertFalse(config.contains("vk1.a.SECRET"));
    }
}
