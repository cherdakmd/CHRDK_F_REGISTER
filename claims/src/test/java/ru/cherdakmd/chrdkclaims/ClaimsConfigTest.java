package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsConfigTest {

    private static ClaimsConfig parse(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(new java.io.StringReader(yaml));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return ClaimsConfig.parse(cfg, Logger.getAnonymousLogger());
    }

    @Test
    void defaults() {
        ClaimsConfig config = parse("general: {}\n");
        assertTrue(config.requireAuth);
        assertEquals(16, config.chunkPrice);
        assertEquals(1, config.upkeepPerDay);
        assertEquals(3, config.graceDays);
        assertEquals(50, config.sellbackPercent);
        assertEquals(10, config.saleTaxPercent);
        assertEquals(24, config.baseChunks);
        assertEquals(8, config.passBonusChunks);
        assertFalse(config.pvp);
        assertTrue(config.explosions);
        assertFalse(config.redstone);
    }

    @Test
    void clampsNegativeAndHugeNumbers() {
        ClaimsConfig config = parse("""
                pricing: {chunk-price: -5, upkeep-per-day: 99999999, grace-days: -1, sellback-percent: 140}
                taxes: {sale-tax-percent: 120}
                limits: {base-chunks: 0, pass-bonus-chunks: -3, members-per-chunk: 900}
                """);
        assertEquals(1, config.chunkPrice);
        assertEquals(100_000, config.upkeepPerDay);
        assertEquals(0, config.graceDays);
        assertEquals(100, config.sellbackPercent);
        assertEquals(90, config.saleTaxPercent);
        assertEquals(1, config.baseChunks);
        assertEquals(0, config.passBonusChunks);
        assertEquals(128, config.membersPerChunk);
    }
}
