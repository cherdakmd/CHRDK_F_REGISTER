package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** События сообщества не включаются молча и не выходят за безопасные границы. */
class SocialEventConfigTest {

    @Test
    void defaultsAndStockConfigAreFullyDisabled() {
        SocialEventConfig cfg = SocialEventConfig.DEFAULT;
        assertFalse(cfg.enabled());
        assertFalse(cfg.playerDeath());
        assertFalse(cfg.serverStart());
        assertFalse(cfg.serverStop());
        assertFalse(cfg.welcomeNewPlayer());
        assertFalse(cfg.anyEnabled());
        assertTrue(cfg.toVk(), "ВК — канал по умолчанию, но только при поднятом enabled");
        assertFalse(cfg.toMax());
        assertFalse(cfg.announceInGame());
        assertEquals(0L, cfg.maxChatId(), "MAX без chat-id выключен");
        assertEquals(30, cfg.cooldownSeconds());
        assertEquals(220, cfg.maxLength());
        assertSame(SocialEventConfig.DEFAULT, SocialEventConfig.parse(null));

        FileConfiguration stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        SocialEventConfig parsed = SocialEventConfig.parse(stock);
        assertFalse(parsed.enabled(), "после обновления плагина беседа не получает сюрпризов");
        assertFalse(parsed.anyEnabled());
        assertTrue(stock.contains("events.player-death"));
        assertTrue(stock.contains("events.welcome-new-player"));
        assertTrue(stock.contains("events.server-start"));
        assertTrue(stock.contains("events.server-stop"));
    }

    @Test
    void flagsAreReadIndependentlyAndNumbersClamp() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("events.enabled", true);
        yaml.set("events.player-death", true);
        yaml.set("events.to-max", true);
        yaml.set("events.max-chat-id", -50);
        yaml.set("events.cooldown-seconds", 99999);
        yaml.set("events.max-length", 99999);
        SocialEventConfig cfg = SocialEventConfig.parse(yaml);
        assertTrue(cfg.anyEnabled());
        assertTrue(cfg.playerDeath());
        assertFalse(cfg.serverStart());
        assertFalse(cfg.serverStop());
        assertFalse(cfg.welcomeNewPlayer());
        assertTrue(cfg.toMax());
        assertEquals(0L, cfg.maxChatId());
        assertEquals(3600, cfg.cooldownSeconds());
        assertEquals(400, cfg.maxLength());
        assertEquals(3_600_000L, cfg.cooldownMillis());

        yaml.set("events.cooldown-seconds", -1);
        yaml.set("events.max-length", -1);
        assertEquals(0, SocialEventConfig.parse(yaml).cooldownSeconds());
        assertEquals(40, SocialEventConfig.parse(yaml).maxLength());
    }

    @Test
    void masterSwitchSilencesEveryEvent() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("events.player-death", true);
        yaml.set("events.server-start", true);
        yaml.set("events.server-stop", true);
        yaml.set("events.welcome-new-player", true);
        assertFalse(SocialEventConfig.parse(yaml).anyEnabled());
        yaml.set("events.enabled", true);
        assertTrue(SocialEventConfig.parse(yaml).anyEnabled());
    }
}
