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
