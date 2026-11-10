package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VkConfigTest {

    @Test
    void stockConfigParsesWithSafeDefaults() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        VkConfig cfg = VkConfig.parse(yaml);
        assertTrue(cfg.enabled());
        assertEquals(0L, cfg.groupId());
        assertFalse(cfg.apiConfigured(), "без токена и group-id сетевой клиент выключен");
        assertEquals("", cfg.photoAccessToken(), "в стоке пользовательский ключ не задан");
        assertFalse(cfg.photoPublishConfigured());
        assertEquals("https://vk.ru/chrdk_reborn", cfg.communityUrl());
        assertEquals(6, cfg.codeLength());
        assertEquals(300, cfg.codeTtlSeconds());
        assertEquals(3, cfg.linkBoostHours());
        assertEquals(3, cfg.memberBoostHours());
        assertEquals(10, cfg.donutShopBonusPercent());
        assertTrue(cfg.allowRemoteLogout());
        assertTrue(cfg.allowRemoteFreeze());
        assertTrue(cfg.groupChatEnabled());
        assertEquals(0L, cfg.groupChatPeerId());
        assertTrue(cfg.groupChatRequireLinked());
        assertTrue(cfg.keyboardsEnabled());
        assertTrue(cfg.inlineSecurityButtons());
        assertTrue(cfg.groupChatResolveMentions());
        assertEquals(500, cfg.groupChatBatchWindowMs());
        assertEquals(VkConfig.DEFAULT_WIKI_URL, cfg.wikiUrl());
        assertEquals("text", cfg.wallPostMode(), "rc.9: стена по умолчанию текстовая");
        assertTrue(cfg.textOnlyWall());
        assertFalse(cfg.photoWallEnabled());
        assertTrue(cfg.chatCardsEnabled());
        assertEquals(VkConfig.DEFAULT_DISCUSS_URL, cfg.discussUrl());
        assertEquals("обсудить — " + VkConfig.DEFAULT_DISCUSS_URL, cfg.discussLine());
        assertEquals("Пост\n\nобсудить — " + VkConfig.DEFAULT_DISCUSS_URL,
                cfg.withDiscuss("Пост"));
    }

    @Test
    void photoModeRequiresUserTokenAndOtherwiseFallsBackToText() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("community.group-id", 123456L);
        yaml.set("community.access-token", "vk1.a.community");
        yaml.set("wall.post-mode", "photo");
        VkConfig cfg = VkConfig.parse(yaml);
        assertEquals("photo", cfg.wallPostMode());
        assertTrue(cfg.textOnlyWall(), "без user token photo автоматически становится text");
        assertFalse(cfg.photoWallEnabled());

        yaml.set("community.photo-user-token", "vk1.a.user");
        cfg = VkConfig.parse(yaml);
        assertTrue(cfg.photoWallEnabled());
        assertFalse(cfg.textOnlyWall());

        yaml.set("wall.post-mode", "TEXT");
        assertEquals("text", VkConfig.parse(yaml).wallPostMode(),
                "неизвестное значение безопасно превращается в text");
    }

    @Test
    void discussLineCanBeDisabledAndLabelIsConfigurable() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall.discuss-label", "Обсудить тут");
        yaml.set("wall.discuss-url", "https://vk.me/join/ABC=");
        VkConfig cfg = VkConfig.parse(yaml);
        assertEquals("https://vk.me/join/ABC=", cfg.discussUrl());
        assertEquals("Обсудить тут — https://vk.me/join/ABC=", cfg.discussLine());

        yaml.set("wall.discuss-url", "");
        cfg = VkConfig.parse(yaml);
        assertEquals("", cfg.discussLine());
        assertEquals("Пост", cfg.withDiscuss("Пост"));

        yaml.set("wall.discuss-url", "http://insecure.example/x");
        assertEquals("", VkConfig.parse(yaml).discussUrl(), "только HTTPS");
    }

    @Test
    void chatCardsCanBeDisabledWithoutDisablingWall() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall.chat-cards", false);
        VkConfig cfg = VkConfig.parse(yaml);
        assertFalse(cfg.chatCardsEnabled());
        assertTrue(cfg.textOnlyWall());
    }

    @Test
    void communityTokenNeverEnablesUnsupportedWallPolling() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall.poll-minutes", 10);
        yaml.set("wall.announce-new-posts", true);
        VkConfig cfg = VkConfig.parse(yaml);
        assertEquals(0, cfg.wallPollMinutes());
        assertTrue(cfg.announceWallPosts(), "События wall_post_new остаются доступны");
    }

    @Test
    void photoUserTokenIsOptionalAndTrimmed() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("community.group-id", 123456L);
        yaml.set("community.access-token", "vk1.a.community");
        VkConfig cfg = VkConfig.parse(yaml);
        assertEquals("", cfg.photoAccessToken());
        assertTrue(cfg.apiConfigured(), "текстовый wall.post работает и без фото-ключа");
        assertFalse(cfg.photoPublishConfigured(), "PNG на стену без пользовательского ключа недоступны");

        yaml.set("community.photo-user-token", "  vk1.a.user  ");
        cfg = VkConfig.parse(yaml);
        assertEquals("vk1.a.user", cfg.photoAccessToken());
        assertTrue(cfg.photoPublishConfigured());
        assertEquals("vk1.a.community", cfg.accessToken(),
                "ключ сообщества не подменяется пользовательским");
    }

    @Test
    void unsafeUrlsFallBackAndNumbersClamp() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("community.group-id", 123456L);
        yaml.set("community.access-token", "vk1.a.secret");
        yaml.set("community.url", "http://insecure.example/'><click:run_command:'/op'>");
        yaml.set("linking.code-length", 99);
        yaml.set("rewards.link-boost-hours", 100);
        yaml.set("rewards.donut-shop-bonus-percent", 95);
        yaml.set("long-poll.wait-seconds", 999);
        yaml.set("group-chat.peer-id", 12345L);

        VkConfig cfg = VkConfig.parse(yaml);
        assertTrue(cfg.apiConfigured());
        assertEquals(VkConfig.DEFAULT_COMMUNITY_URL, cfg.communityUrl(),
                "HTTP и символы разметки отбрасываются");
        assertEquals(10, cfg.codeLength());
        assertEquals(24, cfg.linkBoostHours());
        assertEquals(50, cfg.donutShopBonusPercent());
        assertEquals(25, cfg.longPollWaitSeconds());
        assertEquals(0L, cfg.groupChatPeerId(), "peer_id ниже 2000000001 не является беседой ВК");
        assertEquals("https://vk.ru/wall-123456_77", cfg.wallPostUrl(77L));

        yaml.set("community.url", "https://vk.com/chrdk_reborn");
        assertEquals("https://vk.com/wall-123456_77", VkConfig.parse(yaml).wallPostUrl(77L));

        yaml.set("group-chat.peer-id", 2000000005L);
        assertEquals(2000000005L, VkConfig.parse(yaml).groupChatPeerId());
    }
}
