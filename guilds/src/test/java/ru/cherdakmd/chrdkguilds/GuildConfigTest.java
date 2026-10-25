package ru.cherdakmd.chrdkguilds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml гильдий (1.4.163): умолчания, клампы и реальный файл.
 *
 * <p>Цена создания не может стать отрицательной — иначе гильдии создавались бы
 * с доплатой, — а границы названия и тега не могут противоречить друг другу.</p>
 */
class GuildConfigTest {

    private static final Logger LOGGER = Logger.getLogger("GuildConfigTest");

    private static GuildConfig parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return GuildConfig.parse(config, LOGGER);
    }

    @Test
    void emptyConfigUsesDefaults() {
        GuildConfig config = parse("");
        GuildConfig defaults = GuildConfig.defaults();
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertTrue(config.actionbar());
        assertTrue(config.chatPrefix());
        assertEquals(defaults.creationCost(), config.creationCost());
        assertEquals(defaults.inviteSeconds(), config.inviteSeconds());
        assertEquals(defaults.maxGuilds(), config.maxGuilds());
        assertEquals(defaults.warsEnabled(), config.warsEnabled());
        assertEquals(defaults.minWager(), config.minWager());
        assertEquals(defaults.maxWager(), config.maxWager());
        assertEquals(defaults.warDurationSeconds(), config.warDurationSeconds());
        assertEquals(defaults.warAcceptSeconds(), config.warAcceptSeconds());
        assertEquals(defaults.killWindowSeconds(), config.killWindowSeconds());
        assertTrue(defaults.claimedBonus());
        assertEquals(27, defaults.creationCost());
    }

    @Test
    void generalValuesAreClamped() {
        GuildConfig config = parse("""
                general:
                  creation-cost: -100
                  invite-seconds: 1
                  max-guilds: 0
                  min-name: 10
                  max-name: 4
                """);
        assertEquals(0L, config.creationCost(), "отрицательная цена — бесплатно, не доплата");
        assertEquals(30, config.inviteSeconds());
        assertEquals(1, config.maxGuilds());
        assertEquals(10, config.minName());
        assertEquals(10, config.maxName(), "потолок не может быть ниже минимума");

        GuildConfig huge = parse("""
                general:
                  creation-cost: 99999
                  invite-seconds: 99999
                  max-guilds: 999999
                  max-name: 999
                  max-tag: 999
                """);
        assertEquals(4_096L, huge.creationCost());
        assertEquals(3_600, huge.inviteSeconds());
        assertEquals(GuildConfig.MAX_GUILDS_LIMIT, huge.maxGuilds());
        assertEquals(Guild.MAX_NAME, huge.maxName());
        assertEquals(Guild.MAX_TAG, huge.maxTag());
    }

    @Test
    void warValuesAreClamped() {
        GuildConfig config = parse("""
                wars:
                  min-wager: 500
                  max-wager: 10
                  duration-seconds: 1
                  accept-seconds: 999999
                  kill-window-seconds: -5
                  max-active: 0
                """);
        assertEquals(500L, config.minWager());
        assertEquals(500L, config.maxWager(), "максимум не может быть ниже минимума");
        assertEquals(300, config.warDurationSeconds());
        assertEquals(GuildConfig.MAX_ACCEPT_SECONDS, config.warAcceptSeconds());
        assertEquals(0, config.killWindowSeconds());
        assertEquals(1, config.maxWars());
    }

    @Test
    void bundledConfigIsSane() throws Exception {
        try (InputStream stream = GuildConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            GuildConfig config = GuildConfig.parse(yaml, LOGGER);
            assertTrue(config.enabled());
            assertTrue(config.warsEnabled());
            assertEquals(27L, config.creationCost());
            assertTrue(config.minName() <= config.maxName());
            assertTrue(config.minTag() <= config.maxTag());
            assertTrue(config.minWager() <= config.maxWager());
            assertTrue(config.warDurationSeconds() >= 300);
            assertTrue(config.warAcceptSeconds() >= 30);
            assertFalse(config.locale().isBlank());
            assertNotNull(yaml.getConfigurationSection("levels"),
                    "в конфиге есть подсказка по уровням");
            assertEquals(5, yaml.getInt("levels.max"));
        }
    }
}
