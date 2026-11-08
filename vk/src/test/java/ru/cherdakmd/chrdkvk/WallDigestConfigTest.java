package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Новые ключи необязательны и защищены от некорректных значений. */
class WallDigestConfigTest {

    @Test
    void missingSectionUsesSafeDefaults() {
        WallDigestConfig cfg = WallDigestConfig.parse(new YamlConfiguration());
        assertEquals(WallDigestConfig.DEFAULT, cfg);
        assertTrue(cfg.enabled());
        assertFalse(cfg.showPlayerNames(), "Публичная стена по умолчанию обезличена");
        assertEquals(21, cfg.hour());
        assertEquals(0, cfg.minute());
        assertEquals(1, cfg.minEvents());
    }

    @Test
    void unsafeValuesAreClampedAndBadTimezoneFallsBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall-digest.hour", 99);
        yaml.set("wall-digest.minute", -5);
        yaml.set("wall-digest.poll-minutes", 0);
        yaml.set("wall-digest.max-names", 99);
        yaml.set("wall-digest.timezone", "Not/AZone");

        WallDigestConfig cfg = WallDigestConfig.parse(yaml);
        assertEquals(23, cfg.hour());
        assertEquals(0, cfg.minute());
        assertEquals(1, cfg.pollMinutes());
        assertEquals(5, cfg.maxNames());
        assertEquals("Europe/Moscow", cfg.zone().getId());
    }

    @Test
    void scheduleAndDayKeyUseOwnerTimezone() {
        WallDigestConfig cfg = WallDigestConfig.DEFAULT;
        Instant scheduled = Instant.parse("2026-11-03T18:00:00Z"); // 21:00 МСК
        assertTrue(cfg.isPostTime(scheduled));
        assertFalse(cfg.isPostTime(scheduled.plusSeconds(60)));
        assertTrue(cfg.isRetryTime(scheduled.plusSeconds(180)));
        assertFalse(cfg.isRetryTime(scheduled.plusSeconds(360)));
        assertEquals("2026-11-03", cfg.dayKey(scheduled));
        assertEquals("21:00 Europe/Moscow", cfg.scheduleLabel());
    }

    @Test
    void explicitOverrideKeepOwnerValues() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall-digest.enabled", false);
        yaml.set("wall-digest.show-player-names", true);
        yaml.set("wall-digest.hour", 9);
        WallDigestConfig cfg = WallDigestConfig.parse(yaml);
        assertFalse(cfg.enabled());
        assertTrue(cfg.showPlayerNames());
        assertEquals(9, cfg.hour());
    }
}
