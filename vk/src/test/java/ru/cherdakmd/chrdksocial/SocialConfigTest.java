package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SocialConfigTest {

    @Test
    void stockConfigIsSafeByDefault() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        SocialConfig cfg = SocialConfig.parse(yaml);
        assertTrue(cfg.enabled());
        assertTrue(cfg.requireLink());
        assertEquals(14, cfg.graceDays());
        assertEquals("chrdksocial.bypass", cfg.bypassPermission());
        assertEquals(6, cfg.codeLength());
        assertEquals(300, cfg.codeTtlSeconds());
        assertEquals(0, cfg.rewardXpLevels());
        assertTrue(cfg.failOpenOnCorruption());
        assertEquals("https://platform-api2.max.ru/", cfg.maxApiBaseUrl());
        assertEquals("webhook", cfg.maxTransport());
        assertEquals(8443, cfg.webhookPort());
        assertTrue(cfg.requireTls());
        assertTrue(cfg.pollingEnabled());
        assertTrue(cfg.updateTypes().contains("message_created"));
        assertTrue(cfg.updateTypes().contains("bot_added"));
        assertTrue(cfg.bridgeEnabled());
        assertTrue(cfg.newsEnabled());
        assertFalse(cfg.maxApiConfigured(), "токен бота не задан в репозитории");
        assertFalse(cfg.webhookConfigured(), "нет публичного URL и секрета");
    }

    @Test
    void numericSettingsAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("social.grace-days", 999);
        yaml.set("social.code-length", 1);
        yaml.set("social.code-ttl-seconds", 0);
        yaml.set("max.webhook.port", 99999);
        yaml.set("max.polling.wait-seconds", 999);
        yaml.set("max.transport", "unknown");
        yaml.set("max.update-types", java.util.List.of("message_created", "totally_unknown"));
        SocialConfig cfg = SocialConfig.parse(yaml);
        assertEquals(90, cfg.graceDays());
        assertEquals(4, cfg.codeLength());
        assertEquals(60, cfg.codeTtlSeconds());
        assertEquals(65535, cfg.webhookPort());
        assertEquals(60, cfg.pollingWaitSeconds());
        assertEquals("webhook", cfg.maxTransport());
        assertEquals(java.util.List.of("message_created"), cfg.updateTypes());
    }

    @Test
    void tokenEnablesApiButWebhookStillNeedsHttpsAndSecret() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("max.access-token", "TOKEN");
        SocialConfig cfg = SocialConfig.parse(yaml);
        assertTrue(cfg.maxApiConfigured());
        assertFalse(cfg.webhookConfigured());
        yaml.set("max.webhook.secret", "s3cret");
        yaml.set("max.webhook.public-url", "https://bot.example.ru/max/webhook");
        assertTrue(SocialConfig.parse(yaml).webhookConfigured());
        yaml.set("max.webhook.public-url", "http://plain.example.ru/hook");
        assertFalse(SocialConfig.parse(yaml).webhookConfigured(), "HTTP-webhook MAX не принимает");
    }

    @Test
    void newsTemplateIsFormattedWithoutNetwork() {
        var release = new ReleaseAnnouncement.Release("v1.4.287", "Особые звания", "https://example.ru/r", true);
        String text = ReleaseAnnouncement.format("Выпуск %tag%: %title% → %url%", release);
        assertEquals("Выпуск v1.4.287: Особые звания → https://example.ru/r", text);
    }
}
