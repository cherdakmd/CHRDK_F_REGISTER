package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetsConfigTest {

    @Test
    void defaultsMatchTheShippedConfig() {
        PetsConfig config = PetsConfig.parse(new YamlConfiguration(), null);

        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertEquals(20, config.refreshTicks());
        assertEquals(60, config.saveIntervalSeconds());
        assertEquals(8.0, config.interactRadius(), 1.0e-9);
        assertEquals(12.0, config.xpRadius(), 1.0e-9);
        assertEquals(8.0, config.ownerRadius(), 1.0e-9);
        assertEquals(10, config.topSize());

        assertEquals(10, config.curve().maxLevel());
        assertEquals(40L, config.curve().baseXp());
        assertEquals(25.0, config.curve().growth(), 1.0e-9);

        assertEquals(8, config.xp().perKill());
        assertEquals(5, config.xp().perAssist());
        assertEquals(6, config.xp().perFish());
        assertEquals(25, config.xp().perSleep());
        assertEquals(1.5, config.xp().catMultiplier(), 1.0e-9);
        assertEquals(120, config.xp().maxPerMinute());

        assertTrue(config.species(PetSpecies.WOLF).enabled());
        assertEquals(20.0, config.species(PetSpecies.WOLF).baseHealth(), 1.0e-9);
        assertEquals(18.0, config.species(PetSpecies.WOLF).buffs().maxHealthBonus(), 1.0e-9);
        assertTrue(config.species(PetSpecies.CAT).enabled());
        assertEquals(10.0, config.species(PetSpecies.CAT).baseHealth(), 1.0e-9);
        assertEquals(0.9, config.species(PetSpecies.CAT).buffs().maxOwnerLuck(), 1.0e-9);

        assertEquals(2, config.minNameLength());
        assertEquals(16, config.maxNameLength());
        assertTrue(config.showLevel());
        assertEquals(30, config.keepDeadDays());
    }

    @Test
    void valuesAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("general.refresh-ticks", 1);
        yaml.set("general.save-interval-seconds", 1);
        yaml.set("general.owner-radius", 100.0);
        yaml.set("general.top-size", 1000);
        yaml.set("levels.max-level", 1000);
        yaml.set("levels.base-xp", 0);
        yaml.set("levels.growth", -10.0);
        yaml.set("xp.per-kill", -5);
        yaml.set("xp.max-per-minute", -5);
        yaml.set("species.cat.owner-luck-per-level", -1.0);
        yaml.set("names.min-length", 10);
        yaml.set("names.max-length", 2);
        yaml.set("storage.keep-dead-days", -1);

        PetsConfig config = PetsConfig.parse(yaml, null);

        assertEquals(5, config.refreshTicks());
        assertEquals(5, config.saveIntervalSeconds());
        assertEquals(32.0, config.ownerRadius(), 1.0e-9);
        assertEquals(50, config.topSize());
        assertEquals(100, config.curve().maxLevel());
        assertEquals(1L, config.curve().baseXp());
        assertEquals(0.0, config.curve().growth(), 1.0e-9);
        assertEquals(0, config.xp().perKill());
        assertEquals(0, config.xp().maxPerMinute());
        assertEquals(0.0, config.species(PetSpecies.CAT).buffs().ownerLuckPerLevel(), 1.0e-9);
        assertEquals(10, config.minNameLength());
        assertEquals(10, config.maxNameLength());
        assertEquals(0, config.keepDeadDays());
    }

    @Test
    void speciesCanBeDisabledOneByOne() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("species.wolf.enabled", false);

        PetsConfig config = PetsConfig.parse(yaml, null);

        assertFalse(config.species(PetSpecies.WOLF).enabled());
        assertTrue(config.species(PetSpecies.CAT).enabled());
    }

    @Test
    void unknownSpeciesNeverReturnsNull() {
        PetsConfig config = PetsConfig.parse(new YamlConfiguration(), null);
        assertFalse(config.species(null).enabled());
        assertEquals(0.0, config.species(null).buffs().healthPerLevel(), 1.0e-9);
    }
}
