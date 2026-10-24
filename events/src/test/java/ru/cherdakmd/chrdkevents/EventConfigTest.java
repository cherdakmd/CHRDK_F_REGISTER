package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml мировых событий (1.4.160): умолчания, клампы, отбраковка
 * опечаток и порядок событий.
 */
class EventConfigTest {

    private static final Logger LOGGER = Logger.getLogger("EventConfigTest");

    private static EventConfig parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return EventConfig.parse(config, LOGGER);
    }

    @Test
    void emptyConfigUsesDefaults() {
        EventConfig config = parse("");
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertTrue(config.bossbar());
        assertEquals(1, config.minPlayers());
        assertEquals(600, config.minDelaySeconds());
        assertEquals(1_800, config.maxDelaySeconds());
        assertTrue(config.events().isEmpty());
        assertTrue(config.eventIds().isEmpty());
        assertNull(config.event("нет"));
        assertNull(config.event(null));
    }

    @Test
    void parsesEventOfEachKind() {
        EventConfig config = parse("""
                general:
                  min-players: 3
                  bossbar: false
                schedule:
                  min-delay-seconds: 120
                  max-delay-seconds: 240
                events:
                  knowledge:
                    name: "Час знаний"
                    description: "опыт"
                    type: XP
                    duration-seconds: 300
                    weight: 5
                    percent: 50
                    icon: EXPERIENCE_BOTTLE
                  starfall:
                    type: MINING
                    duration-seconds: 300
                    weight: 4
                    percent: 40
                    blocks: [IRON_ORE, minecraft:gold_ore, ВОЗДУХ]
                    items: [RAW_IRON]
                  blood_moon:
                    type: LOOT
                    duration-seconds: 300
                    weight: 3
                    percent: 25
                    items: [BONE]
                  quiet_hour:
                    type: EFFECT
                    duration-seconds: 300
                    weight: 2
                    effects:
                      - type: regeneration
                        amplifier: 1
                        seconds: 60
                """);
        assertEquals(3, config.minPlayers());
        assertFalse(config.bossbar());
        assertEquals(120, config.minDelaySeconds());
        assertEquals(240, config.maxDelaySeconds());
        assertEquals(4, config.events().size());
        assertEquals(List.of("knowledge", "starfall", "blood_moon", "quiet_hour"),
                config.eventIds());

        WorldEvent knowledge = config.event("KNOWLEDGE");
        assertNotNull(knowledge);
        assertSame(EventKind.XP, knowledge.kind());
        assertEquals("Час знаний", knowledge.name());
        assertEquals("опыт", knowledge.description());
        assertEquals(50, knowledge.percent());
        assertSame(Material.EXPERIENCE_BOTTLE, knowledge.icon());

        WorldEvent starfall = config.event("starfall");
        assertNotNull(starfall);
        assertEquals(List.of(Material.IRON_ORE, Material.GOLD_ORE), starfall.blocks(),
                "неизвестный материал пропущен, namespace снят");
        assertEquals(List.of(Material.RAW_IRON), starfall.items());

        WorldEvent quiet = config.event("quiet_hour");
        assertNotNull(quiet);
        assertEquals(1, quiet.effects().size());
        assertEquals(1, quiet.effects().get(0).amplifier());
        assertEquals(60, quiet.effects().get(0).seconds());
        assertSame(Material.CLOCK, quiet.icon(), "иконка по умолчанию — часы");
    }

    @Test
    void unknownKindIsSkipped() {
        EventConfig config = parse("""
                events:
                  broken:
                    type: МАГИЯ
                    weight: 10
                  fine:
                    type: XP
                    weight: 10
                    percent: 10
                """);
        assertEquals(1, config.events().size());
        assertNotNull(config.event("fine"));
        assertNull(config.event("broken"));
    }

    @Test
    void eventWithoutDataIsSkipped() {
        EventConfig config = parse("""
                events:
                  empty_mining:
                    type: MINING
                    weight: 10
                    percent: 40
                  zero_weight:
                    type: XP
                    weight: 0
                    percent: 40
                  no_percent:
                    type: XP
                    weight: 10
                    percent: 0
                """);
        assertTrue(config.events().isEmpty());
    }

    @Test
    void numbersAreClamped() {
        EventConfig config = parse("""
                general:
                  min-players: 99999
                schedule:
                  min-delay-seconds: 1
                  max-delay-seconds: 999999
                events:
                  clamp:
                    type: XP
                    duration-seconds: 999999
                    weight: 999999
                    cooldown-seconds: 9999999
                    percent: 999
                """);
        assertEquals(EventConfig.MAX_MIN_PLAYERS, config.minPlayers());
        assertEquals(EventConfig.MIN_DELAY_SECONDS, config.minDelaySeconds());
        assertEquals(EventConfig.MAX_DELAY_SECONDS, config.maxDelaySeconds());
        WorldEvent event = config.event("clamp");
        assertNotNull(event);
        assertEquals(WorldEvent.MAX_DURATION_SECONDS, event.durationSeconds());
        assertEquals(WorldEvent.MAX_WEIGHT, event.weight());
        assertEquals(WorldEvent.MAX_COOLDOWN_SECONDS, event.cooldownSeconds());
        assertEquals(100, event.percent());
    }

    @Test
    void swappedScheduleIsFixed() {
        EventConfig config = parse("""
                schedule:
                  min-delay-seconds: 1800
                  max-delay-seconds: 600
                """);
        assertEquals(600, config.minDelaySeconds());
        assertEquals(1_800, config.maxDelaySeconds());
    }

    @Test
    void localeFallsBackToRussian() {
        EventConfig config = parse("""
                general:
                  locale: "  "
                """);
        assertEquals("ru", config.locale());
    }
}
