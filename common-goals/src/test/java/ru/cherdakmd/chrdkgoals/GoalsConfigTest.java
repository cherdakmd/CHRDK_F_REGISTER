package ru.cherdakmd.chrdkgoals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalsConfigTest {

    private static GoalsConfig parse(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(new java.io.StringReader(yaml));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return GoalsConfig.parse(cfg, Logger.getAnonymousLogger());
    }

    @Test
    void defaultsAndClamp() {
        GoalsConfig config = parse("general: {}\n");
        assertTrue(config.requireAuth());
        assertEquals(6, config.activeCount());
        assertEquals(7, config.periodDays());

        GoalsConfig clamped = parse("""
                rotation: {active-count: 99, period-days: 900, reward-bonus-percent: 999}
                """);
        assertEquals(GoalTemplate.ALL.length, clamped.activeCount());
        assertEquals(90, clamped.periodDays());
        assertEquals(200, clamped.rewardBonusPercent());
    }

    @Test
    void templateOverrides() {
        GoalsConfig config = parse("""
                goals:
                  donate-diamonds: {enabled: false, target: 50, reward-pool: 1}
                """);
        assertTrue(config.overrideOf("donate-diamonds").target() == 50);
        assertTrue(!config.overrideOf("donate-diamonds").enabled());
        assertTrue(config.overrideOf("hostile-slay").enabled(), "остальные по умолчанию включены");
    }

    @Test
    void waveRollsDistinctEnabledGoals() {
        GoalsConfig config = parse("""
                rotation: {active-count: 5}
                goals:
                  fish-caught: {enabled: false}
                """);
        for (int seed = 0; seed < 40; seed++) {
            var wave = config.rollWave(new Random(seed));
            assertEquals(5, wave.size());
            var ids = new HashSet<String>();
            for (GoalState goal : wave) {
                assertTrue(ids.add(goal.id), "повторная цель в волне: " + goal.id);
                assertTrue(!"fish-caught".equals(goal.id));
                assertTrue(goal.target >= 1);
            }
        }
    }

    @Test
    void waveCappedByEnabledPool() {
        // выключаем все шаблоны, кроме двух — волна обязана сжаться до включённых
        StringBuilder yaml = new StringBuilder("rotation: {active-count: 8}\ngoals:\n");
        for (GoalTemplate template : GoalTemplate.ALL) {
            if (!"fish-caught".equals(template.id()) && !"donate-diamonds".equals(template.id())) {
                yaml.append("  ").append(template.id()).append(": {enabled: false}\n");
            }
        }
        var wave = parse(yaml.toString()).rollWave(new Random(1));
        assertEquals(2, wave.size(), "только включённые шаблоны, даже если просят больше");
    }
}
