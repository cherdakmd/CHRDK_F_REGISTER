package ru.cherdakmd.chrdkprofessions;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор {@code config.yml} профессий (1.4.165).
 *
 * <p>Опечатка в YAML не должна давать лишний опыт или отключать суточный
 * потолок: всё, что выходит за границы, зажимается, а в лог уходит
 * предупреждение. Это тот же принцип, что у классов и гильдий.</p>
 */
class ProfessionConfigTest {

    private static final Logger LOGGER = Logger.getLogger("ProfessionConfigTest");

    private static ProfessionConfig parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return ProfessionConfig.parse(config, LOGGER);
    }

    @Test
    void defaultsMatchResourceFile() {
        ProfessionConfig config = ProfessionConfig.defaults();
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertTrue(config.actionbar());
        assertTrue(config.joinHint());
        assertEquals(1200, config.dailyXpCap());
        assertEquals(24, config.chooseCooldownHours());
        assertEquals(86_400_000L, config.chooseCooldownMillis());
        assertEquals(1.0, config.xpMultiplier());
        assertTrue(config.perksEnabled());
        assertTrue(config.perkSmithAnvil());
        assertTrue(config.perkAlchemistBottle());
        assertTrue(config.perkCookSaturation());
        assertTrue(config.perkMasonBlock());
    }

    @Test
    void emptyConfigUsesDefaults() throws Exception {
        ProfessionConfig config = parse("");
        assertEquals(1200, config.dailyXpCap());
        assertEquals(24, config.chooseCooldownHours());
        assertEquals(1.0, config.xpMultiplier());
        assertEquals("ru", config.locale());
        assertTrue(config.perkEnabled(Profession.SMITH));
    }

    @Test
    void nullConfigUsesDefaults() {
        ProfessionConfig config = ProfessionConfig.parse(null, LOGGER);
        assertEquals(1200, config.dailyXpCap());
        assertTrue(config.enabled());
    }

    @Test
    void valuesAreClampedToSafeRange() throws Exception {
        ProfessionConfig config = parse("""
                general:
                  daily-xp-cap: 500000
                  choose-cooldown-hours: 5000
                  xp-multiplier: 10.0
                """);
        assertEquals(ProfessionConfig.MAX_DAILY_CAP, config.dailyXpCap());
        assertEquals(ProfessionConfig.MAX_CHOOSE_HOURS, config.chooseCooldownHours());
        assertEquals(ProfessionConfig.MAX_MULTIPLIER, config.xpMultiplier(), 0.0001);

        ProfessionConfig low = parse("""
                general:
                  daily-xp-cap: -50
                  choose-cooldown-hours: -5
                  xp-multiplier: 0.001
                """);
        assertEquals(0, low.dailyXpCap(), "0 — суточный потолок выключен");
        assertEquals(0, low.chooseCooldownHours(), "0 — смена без кулдауна");
        assertEquals(ProfessionConfig.MIN_MULTIPLIER, low.xpMultiplier(), 0.0001);
    }

    @Test
    void disabledSwitchesTurnOffPerks() throws Exception {
        ProfessionConfig config = parse("""
                general:
                  enabled: false
                  actionbar: false
                  join-hint: false
                perks:
                  enabled: false
                """);
        assertFalse(config.enabled());
        assertFalse(config.actionbar());
        assertFalse(config.joinHint());
        assertFalse(config.perksEnabled());
        for (Profession profession : Profession.values()) {
            assertFalse(config.perkEnabled(profession), profession.id());
        }
    }

    @Test
    void singlePerkCanBeTurnedOff() throws Exception {
        ProfessionConfig config = parse("""
                perks:
                  mason-extra-block: false
                """);
        assertTrue(config.perkEnabled(Profession.SMITH));
        assertTrue(config.perkEnabled(Profession.ALCHEMIST));
        assertTrue(config.perkEnabled(Profession.COOK));
        assertFalse(config.perkEnabled(Profession.MASON));
        assertFalse(config.perkEnabled(null));
    }

    @Test
    void localeIsNormalised() throws Exception {
        assertEquals("en", parse("general:\n  locale: EN\n").locale());
        assertEquals("ru", parse("general:\n  locale: \"\"\n").locale());
    }
}
