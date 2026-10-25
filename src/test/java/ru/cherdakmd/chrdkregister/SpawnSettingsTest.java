package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Секция spawn: значения по умолчанию, границы чисел, обрезка имени мира. */
class SpawnSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("SpawnSettingsTest");

    private static SpawnSettings parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return SpawnSettings.parse(configuration, LOGGER);
    }

    @Test
    void missingSectionKeepsFeatureOnWithDefaults() throws Exception {
        // Обновление не должно требовать правки config.yml: секции нет — работают дефолты.
        SpawnSettings settings = parse("general:\n  locale: ru\n");
        assertTrue(settings.enabled());
        assertTrue(settings.onFirstJoin());
        assertTrue(settings.onDeath());
        assertEquals("", settings.world());
        assertEquals(SpawnSettings.DEFAULT_RADIUS, settings.radiusBlocks());
        assertEquals(SpawnSettings.DEFAULT_MIN_RADIUS, settings.minRadiusBlocks());
        assertEquals(SpawnSettings.DEFAULT_ATTEMPTS, settings.maxAttempts());
        assertTrue(settings.avoidClaims());
        assertTrue(settings.respectClaims());
        assertTrue(settings.respectHomes());
        assertTrue(settings.otherDimensions());
    }

    @Test
    void readsEveryKey() throws Exception {
        SpawnSettings settings = parse("""
                spawn:
                  enabled: false
                  on-first-join: false
                  on-death: true
                  world: "  world_nether  "
                  use-world-spawn-center: false
                  center-x: 120
                  center-z: -340
                  radius-blocks: 500
                  min-radius-blocks: 50
                  max-attempts: 5
                  avoid-claims: false
                  respect-claims: false
                  respect-homes: false
                  other-dimensions: "vanilla"
                """);
        assertFalse(settings.enabled());
        assertFalse(settings.onFirstJoin());
        assertTrue(settings.onDeath());
        assertEquals("world_nether", settings.world());
        assertFalse(settings.useWorldSpawnCenter());
        assertEquals(120, settings.centerX());
        assertEquals(-340, settings.centerZ());
        assertEquals(500, settings.radiusBlocks());
        assertEquals(50, settings.minRadiusBlocks());
        assertEquals(5, settings.maxAttempts());
        assertFalse(settings.avoidClaims());
        assertFalse(settings.respectClaims());
        assertFalse(settings.respectHomes());
        assertFalse(settings.otherDimensions(), "vanilla — рандом вне обычного мира выключен");
    }

    @Test
    void invalidNumbersAreClampedNotFatal() throws Exception {
        SpawnSettings settings = parse("""
                spawn:
                  radius-blocks: 0
                  min-radius-blocks: 900000
                  max-attempts: 999
                """);
        assertEquals(1, settings.radiusBlocks(), "радиус не может быть нулевым");
        assertEquals(1, settings.minRadiusBlocks(), "min-radius прижимается к радиусу");
        assertEquals(200, settings.maxAttempts(), "попытки ограничены сверху");
    }

    @Test
    void missingKeysFallBackToDefaults() throws Exception {
        SpawnSettings settings = parse("spawn:\n  radius-blocks: 400\n");
        assertTrue(settings.enabled());
        assertTrue(settings.onDeath());
        assertEquals(400, settings.radiusBlocks());
        assertEquals(SpawnSettings.DEFAULT_MIN_RADIUS, settings.minRadiusBlocks());
    }
}
