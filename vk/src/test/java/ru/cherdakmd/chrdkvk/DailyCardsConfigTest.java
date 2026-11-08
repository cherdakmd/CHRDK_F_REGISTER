package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Новые ключи необязательны, часы клампятся, расписание в поясе владельца. */
class DailyCardsConfigTest {

    @Test
    void missingSectionUsesSafeDefaults() {
        DailyCardsConfig cfg = DailyCardsConfig.parse(new YamlConfiguration());
        assertEquals(DailyCardsConfig.DEFAULT, cfg);
        assertTrue(cfg.enabled());
        assertTrue(cfg.showValues());
        assertNotNull(cfg.footer());
        assertEquals(9, cfg.hour(DailyCardKind.RICHEST));
        assertEquals(12, cfg.hour(DailyCardKind.PRESTIGE));
        assertEquals(15, cfg.hour(DailyCardKind.MINER));
        assertEquals(18, cfg.hour(DailyCardKind.HUNTER));
    }

    @Test
    void explicitHoursAndDisabledStateAreRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall-daily-cards.enabled", false);
        yaml.set("wall-daily-cards.show-values", false);
        yaml.set("wall-daily-cards.richest-hour", 7);
        yaml.set("wall-daily-cards.hunter-hour", 20);
        yaml.set("wall-daily-cards.retry-minutes", 9);
        DailyCardsConfig cfg = DailyCardsConfig.parse(yaml);
        assertFalse(cfg.enabled());
        assertFalse(cfg.showValues());
        assertEquals(7, cfg.richestHour());
        assertEquals(20, cfg.hunterHour());
        assertEquals(9, cfg.retryMinutes());
    }

    @Test
    void unsafeHoursAreClampedAndBadTimezoneFallsBack() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wall-daily-cards.richest-hour", 99);
        yaml.set("wall-daily-cards.prestige-hour", -3);
        yaml.set("wall-daily-cards.retry-minutes", 0);
        yaml.set("wall-daily-cards.timezone", "Not/AZone");
        DailyCardsConfig cfg = DailyCardsConfig.parse(yaml);
        assertEquals(23, cfg.richestHour());
        assertEquals(0, cfg.prestigeHour());
        assertEquals(1, cfg.retryMinutes());
        assertEquals("Europe/Moscow", cfg.zone().getId());
    }

    @Test
    void fourSlotsAndRetryUseOwnerTimezone() {
        DailyCardsConfig cfg = DailyCardsConfig.DEFAULT;
        // 06:00Z = 09:00 МСК, 09:00Z = 12:00 МСК и т.д.
        assertTrue(cfg.isSlotTime(DailyCardKind.RICHEST, Instant.parse("2026-11-03T06:00:00Z")));
        assertTrue(cfg.isSlotTime(DailyCardKind.PRESTIGE, Instant.parse("2026-11-03T09:00:00Z")));
        assertTrue(cfg.isSlotTime(DailyCardKind.MINER, Instant.parse("2026-11-03T12:00:00Z")));
        assertTrue(cfg.isSlotTime(DailyCardKind.HUNTER, Instant.parse("2026-11-03T15:00:00Z")));
        assertFalse(cfg.isSlotTime(DailyCardKind.HUNTER, Instant.parse("2026-11-03T15:01:00Z")));
        assertTrue(cfg.isRetryTime(DailyCardKind.HUNTER, Instant.parse("2026-11-03T15:04:00Z")));
        assertFalse(cfg.isRetryTime(DailyCardKind.HUNTER, Instant.parse("2026-11-03T15:06:00Z")));
        assertEquals("2026-11-03", cfg.dayKey(Instant.parse("2026-11-03T06:00:00Z")));
        assertTrue(cfg.scheduleLabel().contains("21:00"));
    }
}
