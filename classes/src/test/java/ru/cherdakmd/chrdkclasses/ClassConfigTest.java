package ru.cherdakmd.chrdkclasses;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml классов (1.4.164): умолчания, границы и реальный файл.
 *
 * <p>Конфиг управляет темпом, а не балансом, поэтому проверяется именно темп:
 * шаг уровней не может стать нулевым, а кулдаун смены класса — бесконечным.</p>
 */
class ClassConfigTest {

    private static final Logger LOGGER = Logger.getLogger("ClassConfigTest");

    private static ClassConfig parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return ClassConfig.parse(config, LOGGER);
    }

    @Test
    void defaultsWhenConfigIsSilent() {
        ClassConfig config = parse("general: {}\n");
        assertEquals(ClassConfig.defaults().levelsPerLevel(), config.levelsPerLevel());
        assertTrue(config.enabled());
        assertTrue(config.effectsEnabled());
        assertEquals("ru", config.locale());
        assertEquals(30, config.levelsPerLevel());
        assertEquals(1, config.pointsPerLevel());
        assertEquals(1, config.startingPoints());
        assertEquals(7, config.changeCooldownDays());
        assertEquals(24, config.respecCooldownHours());
        assertEquals(5, config.pulseSeconds());
        assertEquals(8, config.outOfCombatSeconds());
        assertTrue(config.abilitiesEnabled());
        assertEquals(1.0, config.abilityCooldownMultiplier(), 1.0E-9);
        assertTrue(config.levelNotice());
        assertTrue(config.tagEnabled(), "метка класса включена по умолчанию");
        ClassConfig nullConfig = ClassConfig.parse(null, LOGGER);
        assertEquals(config.levelsPerLevel(), nullConfig.levelsPerLevel());
    }

    @Test
    void generalValuesAreClamped() {
        ClassConfig low = parse("""
                general:
                  levels-per-level: 1
                  points-per-level: -3
                  starting-points: -1
                  change-cooldown-days: -5
                  respec-cooldown-hours: -2
                  pulse-seconds: 0
                  regen-out-of-combat-seconds: -7
                """);
        assertEquals(5, low.levelsPerLevel());
        assertEquals(0, low.pointsPerLevel());
        assertEquals(0, low.startingPoints());
        assertEquals(0, low.changeCooldownDays());
        assertEquals(0, low.respecCooldownHours());
        assertEquals(1, low.pulseSeconds());
        assertEquals(0, low.outOfCombatSeconds());

        ClassConfig high = parse("""
                general:
                  levels-per-level: 9999
                  points-per-level: 99
                  starting-points: 99
                  change-cooldown-days: 9999
                  respec-cooldown-hours: 99999
                  pulse-seconds: 999
                  regen-out-of-combat-seconds: 999
                """);
        assertEquals(ClassConfig.MAX_LEVELS_PER_LEVEL, high.levelsPerLevel());
        assertEquals(ClassConfig.MAX_POINTS_PER_LEVEL, high.pointsPerLevel());
        assertEquals(ClassConfig.MAX_STARTING_POINTS, high.startingPoints());
        assertEquals(ClassConfig.MAX_CHANGE_DAYS, high.changeCooldownDays());
        assertEquals(ClassConfig.MAX_RESPEC_HOURS, high.respecCooldownHours());
        assertEquals(ClassConfig.MAX_PULSE_SECONDS, high.pulseSeconds());
        assertEquals(ClassConfig.MAX_OUT_OF_COMBAT_SECONDS, high.outOfCombatSeconds());
    }

    @Test
    void abilityMultiplierIsClamped() {
        ClassConfig low = parse("""
                abilities:
                  enabled: false
                  cooldown-multiplier: 0.01
                """);
        assertFalse(low.abilitiesEnabled());
        assertEquals(ClassConfig.MIN_ABILITY_MULTIPLIER, low.abilityCooldownMultiplier(), 1.0E-9);

        ClassConfig high = parse("""
                abilities:
                  cooldown-multiplier: 99
                """);
        assertEquals(ClassConfig.MAX_ABILITY_MULTIPLIER, high.abilityCooldownMultiplier(), 1.0E-9);

        ClassConfig odd = parse("""
                abilities:
                  cooldown-multiplier: "быстро"
                """);
        assertEquals(1.0, odd.abilityCooldownMultiplier(), 1.0E-9);
    }

    @Test
    void derivedDurationsFollowDaysAndHours() {
        ClassConfig config = parse("""
                general:
                  change-cooldown-days: 2
                  respec-cooldown-hours: 3
                  pulse-seconds: 10
                """);
        assertEquals(172_800L, config.changeCooldownSeconds());
        assertEquals(10_800L, config.respecCooldownSeconds());
        assertEquals(200L, config.pulseTicks());
        assertEquals(0L, parse("""
                general:
                  change-cooldown-days: 0
                """).changeCooldownSeconds());
    }

    @Test
    void localeIsNormalised() {
        assertEquals("en", parse("""
                general:
                  locale: " EN "
                """).locale());
        assertEquals("ru", parse("""
                general:
                  locale: ""
                """).locale());
    }

    @Test
    void bundledConfigIsSane() throws Exception {
        try (InputStream stream = ClassConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ClassConfig config = ClassConfig.parse(yaml, LOGGER);
            assertTrue(config.enabled());
            assertTrue(config.effectsEnabled());
            assertEquals(30, config.levelsPerLevel());
            assertEquals(1, config.pointsPerLevel());
            assertEquals(5, config.pulseSeconds());
            assertTrue(config.outOfCombatSeconds() >= 3, "регенерация не должна идти в бою");
            assertFalse(config.locale().isBlank());
            assertNotNull(yaml.getConfigurationSection("effects"));
            assertTrue(config.abilitiesEnabled(), "умения идут включёнными");
            assertEquals(1.0, config.abilityCooldownMultiplier(), 1.0E-9);
            assertNotNull(yaml.getConfigurationSection("abilities"));
            assertTrue(yaml.getBoolean("general.level-notice"));
            assertTrue(config.tagEnabled());
            assertNotNull(yaml.getConfigurationSection("tag"));
            assertNotNull(yaml.getConfigurationSection("points"), "секция очков за достижения");
            assertNotNull(yaml.getConfigurationSection("ranks"), "секция плюшек рангов");
            assertEquals(15, config.achievementEvery());
            assertEquals(2, config.achievementMaxBonus());
            assertTrue(config.ranksEnabled());
        }
    }

    @Test
    void achievementPointsRespectTheirLimits() {
        ClassConfig defaults = parse("general: {}\n");
        assertEquals(ClassConfig.defaults().achievementEvery(), defaults.achievementEvery());
        assertEquals(ClassConfig.defaults().achievementMaxBonus(), defaults.achievementMaxBonus());
        assertTrue(defaults.ranksEnabled(), "плюшки рангов включены по умолчанию");

        ClassConfig low = parse("""
                points:
                  achievement-every: -5
                  achievement-max: -5
                ranks:
                  enabled: false
                """);
        assertEquals(0, low.achievementEvery(), "отрицательное значение — бонус выключен");
        assertEquals(0, low.achievementMaxBonus());
        assertFalse(low.ranksEnabled());

        ClassConfig high = parse("""
                points:
                  achievement-every: 999
                  achievement-max: 999
                """);
        assertEquals(ClassConfig.MAX_ACHIEVEMENT_EVERY, high.achievementEvery());
        assertEquals(ClassConfig.MAX_ACHIEVEMENT_BONUS, high.achievementMaxBonus(),
                "потолок очков за достижения не даёт купить всё дерево дважды");
    }

    @Test
    void levelStepNeverCollapsesToOne() {
        ClassConfig low = parse("""
                general:
                  levels-per-level: 1
                """);
        assertEquals(ClassConfig.MIN_LEVELS_PER_LEVEL, low.levelsPerLevel());
    }
}
