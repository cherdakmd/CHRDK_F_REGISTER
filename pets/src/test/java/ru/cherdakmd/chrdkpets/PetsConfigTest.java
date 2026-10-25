package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetsConfigTest {

    private static final Logger LOGGER = Logger.getLogger("PetsConfigTest");

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void emptyConfigFallsBackToDefaults() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml(""), LOGGER);
        PetsConfig defaults = PetsConfig.defaults();
        assertEquals(defaults.enabled(), config.enabled());
        assertEquals(defaults.locale(), config.locale());
        assertEquals(defaults.refreshTicks(), config.refreshTicks());
        assertEquals(defaults.saveIntervalSeconds(), config.saveIntervalSeconds());
        assertEquals(defaults.ownerRadius(), config.ownerRadius(), 1.0e-9);
        assertEquals(defaults.xpRadius(), config.xpRadius(), 1.0e-9);
        assertEquals(defaults.topSize(), config.topSize());
        assertEquals(defaults.curve(), config.curve());
        assertEquals(defaults.xp(), config.xp());
        assertEquals(defaults.minNameLength(), config.minNameLength());
        assertEquals(defaults.maxNameLength(), config.maxNameLength());
        assertEquals(defaults.keepDeadDays(), config.keepDeadDays());
        assertEquals(defaults.species(PetSpecies.WOLF), config.species(PetSpecies.WOLF));
        assertEquals(defaults.species(PetSpecies.CAT), config.species(PetSpecies.CAT));
    }

    @Test
    void parseNullIsDefaults() {
        assertEquals(PetsConfig.defaults().curve(), PetsConfig.parse(null, LOGGER).curve());
    }

    @Test
    void fullConfigIsRead() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml("""
                general:
                  enabled: false
                  locale: en
                  refresh-ticks: 40
                  save-interval-seconds: 120
                  owner-radius: 10
                  xp-radius: 16
                  top-size: 15
                levels:
                  max-level: 20
                  xp-base: 50
                  xp-growth: 30
                xp:
                  kill: 10
                  assist: 6
                  fish: 7
                  sleep: 30
                  cat-multiplier: 2.0
                  max-per-minute: 0
                names:
                  min-length: 3
                  max-length: 20
                  show-level: false
                storage:
                  keep-dead-days: 7
                """), LOGGER);
        assertFalse(config.enabled());
        assertEquals("en", config.locale());
        assertEquals(40, config.refreshTicks());
        assertEquals(120, config.saveIntervalSeconds());
        assertEquals(10.0, config.ownerRadius(), 1.0e-9);
        assertEquals(16.0, config.xpRadius(), 1.0e-9);
        assertEquals(15, config.topSize());
        assertEquals(new PetCurve.Params(20, 50L, 30.0), config.curve());
        assertEquals(new PetsConfig.Xp(10, 6, 7, 30, 2.0, 0), config.xp());
        assertEquals(3, config.minNameLength());
        assertEquals(20, config.maxNameLength());
        assertFalse(config.showLevel());
        assertEquals(7, config.keepDeadDays());
    }

    @Test
    void speciesSectionIsRead() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml("""
                species:
                  wolf:
                    enabled: false
                    health-per-level: 1.0
                    max-health-bonus: 5.0
                    damage-per-level: 0.5
                    max-damage-bonus: 3.0
                    speed-percent-per-level: 0.2
                    max-speed-percent: 1.0
                    owner-speed-percent-per-level: 0.1
                    max-owner-speed-percent: 0.5
                    owner-luck-per-level: 0.0
                    max-owner-luck: 0.0
                  cat:
                    owner-luck-per-level: 0.2
                    max-owner-luck: 1.5
                """), LOGGER);
        PetsConfig.Species wolf = config.species(PetSpecies.WOLF);
        assertFalse(wolf.enabled());
        assertEquals(1.0, wolf.buffs().healthPerLevel(), 1.0e-9);
        assertEquals(5.0, wolf.buffs().maxHealthBonus(), 1.0e-9);
        assertEquals(0.5, wolf.buffs().damagePerLevel(), 1.0e-9);
        assertEquals(3.0, wolf.buffs().maxDamageBonus(), 1.0e-9);
        assertEquals(0.2, wolf.buffs().speedPercentPerLevel(), 1.0e-9);
        assertEquals(1.0, wolf.buffs().maxSpeedPercent(), 1.0e-9);
        assertEquals(0.1, wolf.buffs().ownerSpeedPercentPerLevel(), 1.0e-9);
        assertEquals(0.5, wolf.buffs().maxOwnerSpeedPercent(), 1.0e-9);

        PetsConfig.Species cat = config.species(PetSpecies.CAT);
        assertTrue(cat.enabled());
        assertEquals(1.0, cat.buffs().healthPerLevel(), 1.0e-9);
        assertEquals(0.2, cat.buffs().ownerLuckPerLevel(), 1.0e-9);
        assertEquals(1.5, cat.buffs().maxOwnerLuck(), 1.0e-9);
    }

    @Test
    void missingSpeciesSectionDisablesNothingButKeepsDefaults() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml("general:\n  enabled: true\n"), LOGGER);
        assertTrue(config.species(PetSpecies.WOLF).enabled());
        assertTrue(config.species(PetSpecies.CAT).enabled());
        assertEquals(PetsConfig.Species.OFF, config.species(null));
    }

    @Test
    void wildValuesAreClamped() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml("""
                general:
                  refresh-ticks: 1
                  save-interval-seconds: 5
                  owner-radius: 999
                  xp-radius: 1
                  top-size: 1000
                levels:
                  max-level: 1000
                  xp-base: 0
                  xp-growth: -40
                xp:
                  kill: 999
                  assist: -5
                  fish: 999
                  sleep: 9999
                  cat-multiplier: 50
                  max-per-minute: -10
                names:
                  min-length: 40
                  max-length: 1
                storage:
                  keep-dead-days: -5
                """), LOGGER);
        assertEquals(10, config.refreshTicks());
        assertEquals(15, config.saveIntervalSeconds());
        assertEquals(32.0, config.ownerRadius(), 1.0e-9);
        assertEquals(4.0, config.xpRadius(), 1.0e-9);
        assertEquals(50, config.topSize());
        assertEquals(100, config.curve().maxLevel());
        assertEquals(1L, config.curve().baseXp());
        assertEquals(0.0, config.curve().growth(), 1.0e-9);
        assertEquals(100, config.xp().kill());
        assertEquals(0, config.xp().assist());
        assertEquals(3.0, config.xp().catMultiplier(), 1.0e-9);
        assertEquals(0, config.xp().maxPerMinute());
        assertEquals(32, config.minNameLength());
        assertEquals(32, config.maxNameLength());
        assertEquals(0, config.keepDeadDays());
    }

    @Test
    void nameRangeNeverInverts() throws Exception {
        PetsConfig config = PetsConfig.parse(yaml("""
                names:
                  min-length: 10
                  max-length: 4
                """), LOGGER);
        assertEquals(10, config.minNameLength());
        assertEquals(10, config.maxNameLength());
    }

    @Test
    void localeIsNormalised() throws Exception {
        assertEquals("en", PetsConfig.parse(yaml("general:\n  locale: EN\n"), LOGGER).locale());
        assertEquals("ru", PetsConfig.parse(yaml("general:\n  locale: '  '\n"), LOGGER).locale());
    }

    @Test
    void speciesEnumsResolveByConfigId() {
        assertEquals(PetSpecies.WOLF, PetSpecies.byId("wolf"));
        assertEquals(PetSpecies.CAT, PetSpecies.byId(" CAT "));
        assertNull(PetSpecies.byId("dragon"));
        assertNull(PetSpecies.byId(null));
    }
}
