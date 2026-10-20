package ru.cherdakmd.chrdkbp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Настройки: старый конфиг без новых ключей, зажим значений, полный разбор. */
class BattlePassSettingsTest {

    private static BattlePassSettings parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return BattlePassSettings.parse(configuration);
    }

    @Test
    void missingSectionUsesDefaults() throws Exception {
        BattlePassSettings settings = parse("general:\n  locale: ru\n");
        assertEquals(BattlePassSettings.defaults(), settings);
    }

    @Test
    void oldConfigWithoutNewKeysKeepsDefaults() throws Exception {
        BattlePassSettings settings = parse("""
                battlepass:
                  season-name: "Сезон"
                  season-start: "2026-01-01"
                  weeks: []
                """);
        assertFalse(settings.countCreative());
        assertTrue(settings.ignorePlayerPlaced());
        assertEquals(8, settings.barWidth());
        assertEquals(300, settings.autosaveSeconds());
        assertTrue(settings.matchVariants());
    }

    @Test
    void readsAllKeys() throws Exception {
        BattlePassSettings settings = parse("""
                battlepass:
                  autosave-seconds: 120
                  match-variants: false
                  fair-play:
                    count-creative: true
                    ignore-player-placed: false
                    placed-memory-seconds: 90
                  display:
                    bar-width: 12
                    sounds: false
                    actionbar: false
                    announce-new-waves: false
                    menu: false
                """);
        assertTrue(settings.countCreative());
        assertFalse(settings.ignorePlayerPlaced());
        assertEquals(90, settings.placedMemorySeconds());
        assertFalse(settings.matchVariants());
        assertEquals(12, settings.barWidth());
        assertFalse(settings.sounds());
        assertFalse(settings.actionbar());
        assertFalse(settings.announceNewWaves());
        assertFalse(settings.menuEnabled());
        assertEquals(120, settings.autosaveSeconds());
    }

    @Test
    void sillyValuesAreClamped() throws Exception {
        BattlePassSettings tooSmall = parse("""
                battlepass:
                  autosave-seconds: 1
                  fair-play:
                    placed-memory-seconds: 1
                  display:
                    bar-width: 1
                """);
        assertEquals(BattlePassSettings.MIN_AUTOSAVE_SECONDS, tooSmall.autosaveSeconds());
        assertEquals(BattlePassSettings.MIN_MEMORY_SECONDS, tooSmall.placedMemorySeconds());
        assertEquals(BattlePassSettings.MIN_BAR_WIDTH, tooSmall.barWidth());

        BattlePassSettings tooBig = parse("""
                battlepass:
                  autosave-seconds: 999999
                  fair-play:
                    placed-memory-seconds: 999999
                  display:
                    bar-width: 400
                """);
        assertEquals(BattlePassSettings.MAX_AUTOSAVE_SECONDS, tooBig.autosaveSeconds());
        assertEquals(BattlePassSettings.MAX_MEMORY_SECONDS, tooBig.placedMemorySeconds());
        assertEquals(BattlePassSettings.MAX_BAR_WIDTH, tooBig.barWidth());
    }

    @Test
    void premiumDefaultsOn() throws Exception {
        BattlePassSettings settings = parse("battlepass:\n  season-start: \"2026-01-01\"\n");
        assertTrue(settings.premiumEnabled());
        assertEquals("battlepass-premium", settings.premiumProduct());
    }

    @Test
    void premiumSectionIsParsed() throws Exception {
        BattlePassSettings settings = parse("""
                battlepass:
                  premium:
                    enabled: false
                    product: " Season-Pass "
                """);
        assertFalse(settings.premiumEnabled());
        assertEquals("season-pass", settings.premiumProduct());
    }

    @Test
    void brokenPremiumProductFallsBack() throws Exception {
        BattlePassSettings settings = parse("""
                battlepass:
                  premium:
                    product: "плохой id!"
                """);
        assertEquals("battlepass-premium", settings.premiumProduct(),
                "кривой id не должен ломать выдачу наград");
    }
}
