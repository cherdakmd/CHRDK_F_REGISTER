package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Настройки банка алмазов (1.4.138): дефолты, чтение {@code economy.*} и
 * защитные границы — отрицательный курс или гигантская комиссия не должны
 * попадать в живую экономику.
 */
class EconomySettingsTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(new StringReader(text));
        return configuration;
    }

    @Test
    void missingSectionGivesSafeDefaults() {
        assertEquals(EconomySettings.DEFAULTS, EconomySettings.from(null));
        var empty = new YamlConfiguration();
        assertEquals(EconomySettings.DEFAULTS, EconomySettings.from(empty));
        assertTrue(EconomySettings.from(empty).enabled());
        assertEquals(9, EconomySettings.from(empty).blockWorth());
    }

    @Test
    void valuesAreRead() throws Exception {
        var settings = EconomySettings.from(yaml("""
                economy:
                  enabled: false
                  block-worth: 4
                  min-pay: 5
                  max-pay: 100
                  pay-tax-percent: 3
                  pay-cooldown-seconds: 7
                  baltop-size: 20
                  allow-offline-pay: false
                """));
        assertFalse(settings.enabled());
        assertEquals(4, settings.blockWorth());
        assertEquals(5L, settings.minPay());
        assertEquals(100L, settings.maxPay());
        assertEquals(3, settings.payTaxPercent());
        assertEquals(7, settings.payCooldownSeconds());
        assertEquals(20, settings.baltopSize());
        assertFalse(settings.allowOfflinePay());
    }

    @Test
    void clampsDangerousValues() {
        var settings = new EconomySettings(true, 0, 0L, 0L, 500, -10, 1_000, true);
        assertEquals(1, settings.blockWorth(), "курс блока не может быть нулевым");
        assertEquals(1L, settings.minPay());
        assertEquals(1L, settings.maxPay(), "максимум не ниже минимума");
        assertEquals(50, settings.payTaxPercent(), "комиссия не больше половины");
        assertEquals(0, settings.payCooldownSeconds());
        assertEquals(50, settings.baltopSize());
        var huge = new EconomySettings(true, 999, 10L, 5L, 0, 99_999, 1, true);
        assertEquals(64, huge.blockWorth());
        assertEquals(10L, huge.maxPay(), "максимум подтягивается до минимума");
        assertEquals(3_600, huge.payCooldownSeconds());
        assertEquals(3, huge.baltopSize());
    }
}
