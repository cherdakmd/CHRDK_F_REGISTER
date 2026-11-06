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
        assertEquals(14, cfg.newcomerGraceDays());
        assertTrue(cfg.donateEnabled());
        assertEquals(SocialAccessPolicy.MODE_EITHER, cfg.donateMode(),
                "любой донатный ранг ИЛИ карточка проходки снимают ограничение");
        assertEquals(java.util.List.of("social-access", "social-access-day"),
                cfg.donateProductIds(), "платный доступ дают оба тарифа проходки");
        assertFalse(cfg.donatePermanent(), "по умолчанию доступ привязан к активной покупке");
        assertEquals("https://chrdk.trademc.org/", cfg.donateUrl());
        assertTrue(cfg.donateActive());
        assertEquals(180, cfg.reminderSeconds(), "напоминание в чате не чаще раза в 3 минуты");
        assertTrue(cfg.reminderNagging());
        assertEquals(15, cfg.reminderActionBarSeconds());
        assertEquals(120, cfg.reminderActionNagSeconds());
        assertTrue(cfg.reminderTitleOnJoin());
        assertTrue(cfg.reminderSound());
    }

    @Test
    void donateModeIsFailClosedAndProductIdsAreSanitized() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("social.newcomer-grace-days", 999);
        yaml.set("social.donate-access.mode", "unknown");
        yaml.set("social.donate-access.product-ids",
                java.util.List.of("social-pass", "social-pass", "bad id with spaces", "rank-keeper"));
        yaml.set("social.donate-access.url", "http://plain.example.ru/donate");
        SocialConfig cfg = SocialConfig.parse(yaml);
        assertEquals(90, cfg.newcomerGraceDays());
        assertEquals(SocialAccessPolicy.MODE_OFF, cfg.donateMode());
        assertFalse(cfg.donateActive());
        assertEquals(java.util.List.of("social-pass", "rank-keeper"), cfg.donateProductIds());
        assertEquals("https://chrdk.trademc.org/", cfg.donateUrl(),
                "только HTTPS-адрес попадает в кликабельное сообщение");
        yaml.set("social.donate-access.mode", "product-list");
        assertTrue(SocialConfig.parse(yaml).donateActive());
        yaml.set("social.donate-access.enabled", false);
        assertFalse(SocialConfig.parse(yaml).donateActive());
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
        yaml.set("max.webhook.public-url", "https://bot.example.ru/max/webhook");
        yaml.set("max.webhook.secret", "short with spaces!");
        assertFalse(SocialConfig.parse(yaml).webhookConfigured(),
                "MAX принимает secret только из A-Z a-z 0-9 _ - длиной 5..256");
        yaml.set("max.webhook.secret", "Chrdk-2026_webhook_secret");
        assertTrue(SocialConfig.parse(yaml).webhookConfigured());
    }

    @Test
    void newsTemplateIsFormattedWithoutNetwork() {
        var release = new ReleaseAnnouncement.Release("v1.4.287", "Особые звания", "https://example.ru/r", true);
        String text = ReleaseAnnouncement.format("Выпуск %tag%: %title% → %url%", release);
        assertEquals("Выпуск v1.4.287: Особые звания → https://example.ru/r", text);
    }
}
