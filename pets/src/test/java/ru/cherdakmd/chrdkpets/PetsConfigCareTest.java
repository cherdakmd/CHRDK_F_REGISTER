package ru.cherdakmd.chrdkpets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetsConfigCareTest {

    private static PetsConfig parse(String yaml) {
        return PetsConfig.parse(YamlConfiguration.loadConfiguration(new StringReader(yaml)), null);
    }

    @Test
    void readsCareTricksMovementAndCosmetics() {
        PetsConfig config = parse("""
                general:
                  max-pets: 3
                  top-size: 5
                care:
                  enabled: false
                  start-satiety: 10
                  feed-cooldown-seconds: 5
                tricks:
                  cooldown-seconds: 7
                  learn-chance-base: 0.5
                movement:
                  call-cooldown-seconds: 11
                  rename-cooldown-seconds: 13
                cosmetics:
                  collar-enabled: false
                  glow-enabled: false
                diary:
                  size: 4
                xp:
                  per-feed: 9
                  per-pat: 3
                  near-bonus: 1.5
                  near-radius: 6
                  night-bonus: 2.0
                  night-wolf-only: false
                  weekend-bonus: 1.75
                  weekend-days: [FRIDAY, "nope"]
                  disabled-worlds: ["World_Nether"]
                names:
                  auto-wolf: [A, B]
                  auto-cat: [C]
                """);
        assertEquals(3, config.maxPets());
        assertEquals(5, config.topSize());
        assertFalse(config.careEnabled());
        assertEquals(10, config.care().startSatiety());
        assertEquals(5, config.care().feedCooldownSeconds());
        assertEquals(9, config.care().feedXp());
        assertEquals(3, config.care().patXp());
        assertEquals(7, config.tricks().cooldownSeconds());
        assertEquals(0.5, config.tricks().learnChanceBase(), 1.0e-9);
        assertEquals(11, config.movement().callCooldownSeconds());
        assertEquals(13, config.movement().renameCooldownSeconds());
        assertFalse(config.cosmetics().collarEnabled());
        assertFalse(config.cosmetics().glowEnabled());
        assertEquals(4, config.diarySize());
        assertEquals(1.5, config.bonuses().near(), 1.0e-9);
        assertEquals(6.0, config.bonuses().nearRadius(), 1.0e-9);
        assertEquals(2.0, config.bonuses().night(), 1.0e-9);
        assertFalse(config.bonuses().nightWolfOnly());
        assertEquals(1.75, config.bonuses().weekend(), 1.0e-9);
        assertTrue(config.bonuses().weekend(DayOfWeek.FRIDAY));
        assertFalse(config.bonuses().weekend(DayOfWeek.SATURDAY));
        assertTrue(config.disabledWorlds().contains("world_nether"));
        assertEquals(List.of("A", "B"), config.autoNames(PetSpecies.WOLF));
        assertEquals(List.of("C"), config.autoNames(PetSpecies.CAT));
    }

    @Test
    void defaultsAreSafeOnEmptyConfig() {
        PetsConfig config = parse("");
        assertEquals(0, config.maxPets());
        assertTrue(config.careEnabled());
        assertEquals(70, config.care().startSatiety());
        assertEquals(50, config.care().moodNeutral());
        assertEquals(8, config.diarySize());
        assertTrue(config.disabledWorlds().isEmpty());
        assertTrue(config.bonuses().weekendDays().isEmpty());
        assertTrue(config.cosmetics().collarEnabled());
        assertTrue(config.cosmetics().glowEnabled());
        assertTrue(config.autoNames(PetSpecies.WOLF).isEmpty());
        assertEquals(10, config.curve().maxLevel());
        assertEquals(10, config.topSize());
    }

    @Test
    void bonusValuesAreClamped() {
        PetsConfig config = parse("""
                xp:
                  near-bonus: 99
                  night-bonus: 0.1
                  weekend-bonus: -5
                  near-radius: 0
                care:
                  start-satiety: 500
                  feed-cooldown-seconds: -1
                """);
        assertEquals(3.0, config.bonuses().near(), 1.0e-9);
        assertEquals(1.0, config.bonuses().night(), 1.0e-9);
        assertEquals(1.0, config.bonuses().weekend(), 1.0e-9);
        assertEquals(1.0, config.bonuses().nearRadius(), 1.0e-9);
        assertEquals(100, config.care().startSatiety());
        assertEquals(0, config.care().feedCooldownSeconds());
    }
}
