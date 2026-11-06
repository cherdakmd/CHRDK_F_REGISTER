package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Интерактив не включается молча: сток и дефолты целиком выключены. */
class SocialInteractionConfigTest {

    @Test
    void defaultsAndStockAreDisabled() {
        SocialInteractionConfig cfg = SocialInteractionConfig.DEFAULT;
        assertFalse(cfg.enabled());
        assertFalse(cfg.callbacks());
        assertFalse(cfg.dialogs());
        assertFalse(cfg.polls());
        assertFalse(cfg.tickets());
        assertFalse(cfg.wallReactions());
        assertFalse(cfg.active());
        assertEquals(0L, cfg.adminPeerId());
        assertEquals(15, cfg.cooldownSeconds());
        assertSame(SocialInteractionConfig.DEFAULT, SocialInteractionConfig.parse(null));

        FileConfiguration stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        assertFalse(SocialInteractionConfig.parse(stock).enabled());
        assertTrue(stock.contains("interactive.callbacks"));
        assertTrue(stock.contains("interactive.wall-reactions"));
        assertTrue(stock.contains("interactive.poll-question"));
    }

    @Test
    void numbersClampAndQuestionFallsBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("interactive.enabled", true);
        yaml.set("interactive.callbacks", true);
        yaml.set("interactive.cooldown-seconds", 99999);
        yaml.set("interactive.dialog-ttl-seconds", -1);
        yaml.set("interactive.poll-duration-seconds", -1);
        yaml.set("interactive.max-length", 99999);
        yaml.set("interactive.admin-peer-id", -50);
        yaml.set("interactive.poll-question", "   ");
        SocialInteractionConfig cfg = SocialInteractionConfig.parse(yaml);
        assertTrue(cfg.active());
        assertEquals(3600, cfg.cooldownSeconds());
        assertEquals(30, cfg.dialogTtlSeconds());
        assertEquals(30, cfg.pollDurationSeconds());
        assertEquals(400, cfg.maxLength());
        assertEquals(0L, cfg.adminPeerId());
        assertEquals(SocialInteractionConfig.DEFAULT.pollQuestion(), cfg.pollQuestion());
        assertEquals(3_600_000L, cfg.cooldownMillis());
        assertEquals(120_000L, SocialInteractionConfig.DEFAULT.dialogTtlMillis());
    }
}
