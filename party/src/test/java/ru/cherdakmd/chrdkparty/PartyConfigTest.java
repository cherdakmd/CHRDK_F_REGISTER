package ru.cherdakmd.chrdkparty;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyConfigTest {

    private static final Logger LOGGER = Logger.getLogger("PartyConfigTest");

    private static YamlConfiguration shipped() {
        try (var stream = PartyConfigTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml не найден в ресурсах");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            throw new AssertionError("не удалось прочитать config.yml", exception);
        }
    }

    @Test
    void emptyConfigFallsBackToDefaults() {
        PartyConfig config = PartyConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertEquals(8, config.maxSize());
        assertEquals(60, config.inviteExpireSeconds());
        assertEquals(60_000L, config.inviteExpireMillis());
        assertEquals(5, config.inviteMaxPending());
        assertTrue(config.bonusEnabled());
        assertEquals(24, config.bonusRadius());
        assertEquals(5, config.perMemberPercent());
        assertEquals(15, config.maxBonusPercent());
        assertFalse(config.friendlyFire(), "своих по умолчанию не бьём");
    }

    @Test
    void shippedConfigMatchesDocumentedDefaults() {
        PartyConfig config = PartyConfig.parse(shipped(), LOGGER);
        assertEquals(PartyConfig.defaults().maxSize(), config.maxSize());
        assertEquals(PartyConfig.defaults().maxBonusPercent(), config.maxBonusPercent());
        assertEquals(PartyConfig.defaults().perMemberPercent(), config.perMemberPercent());
        assertEquals(PartyConfig.defaults().bonusRadius(), config.bonusRadius());
        assertEquals(PartyConfig.defaults().inviteExpireSeconds(), config.inviteExpireSeconds());
        assertTrue(config.enabled(), "в config.yml группы выключены");
        assertTrue(config.bonusEnabled());
        assertFalse(config.friendlyFire());
    }

    @Test
    void brokenValuesAreClamped() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("limits.max-size", 5000);
        cfg.set("limits.invite-expire-seconds", 1);
        cfg.set("limits.invite-max-pending", 0);
        cfg.set("bonus.radius", 10_000);
        cfg.set("bonus.per-member-percent", -10);
        cfg.set("bonus.max-percent", 100_000);
        PartyConfig config = PartyConfig.parse(cfg, LOGGER);
        assertEquals(PartyConfig.MAX_SIZE_LIMIT, config.maxSize());
        assertEquals(10, config.inviteExpireSeconds(), "минимум — 10 секунд");
        assertEquals(1, config.inviteMaxPending());
        assertEquals(PartyConfig.MAX_RADIUS, config.bonusRadius());
        assertEquals(0, config.perMemberPercent());
        assertEquals(PartyConfig.MAX_BONUS_PERCENT, config.maxBonusPercent());
    }

    @Test
    void minimumPartyIsTwo() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("limits.max-size", 1);
        assertEquals(2, PartyConfig.parse(cfg, LOGGER).maxSize(),
                "группа из одного человека — это не группа");
    }

    @Test
    void defaultsAndConstantsAgree() {
        PartyConfig defaults = PartyConfig.defaults();
        assertEquals(Party.MAX_SIZE_LIMIT, PartyConfig.MAX_SIZE_LIMIT);
        assertTrue(defaults.maxSize() <= Party.MAX_SIZE_LIMIT);
        assertTrue(defaults.maxBonusPercent() <= PartyConfig.MAX_BONUS_PERCENT);
        assertTrue(defaults.bonusRadius() <= PartyConfig.MAX_RADIUS);
    }
}
