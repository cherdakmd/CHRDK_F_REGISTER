package ru.cherdakmd.chrdkgoals;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Разбор config.yml: ротация, правило «только авторизованные», параметры шаблонов. */
public final class GoalsConfig {

    public record TemplateOverride(boolean enabled, int target, int rewardPool) {
    }

    private final boolean requireAuth;
    private final int activeCount;
    private final int periodDays;
    private final int rewardBonusPercent;
    private final Map<String, TemplateOverride> templates;

    private GoalsConfig(boolean requireAuth, int activeCount, int periodDays,
                        int rewardBonusPercent, Map<String, TemplateOverride> templates) {
        this.requireAuth = requireAuth;
        this.activeCount = activeCount;
        this.periodDays = periodDays;
        this.rewardBonusPercent = rewardBonusPercent;
        this.templates = templates;
    }

    public static GoalsConfig parse(FileConfiguration cfg, Logger logger) {
        boolean requireAuth = cfg.getBoolean("general.require-auth", true);
        int activeCount = clamp(cfg.getInt("rotation.active-count", 4), 1, GoalTemplate.ALL.length);
        int periodDays = clamp(cfg.getInt("rotation.period-days", 7), 1, 90);
        int bonus = clamp(cfg.getInt("rotation.reward-bonus-percent", 0), 0, 200);
        Map<String, TemplateOverride> templates = new HashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("goals");
        for (GoalTemplate template : GoalTemplate.ALL) {
            boolean enabled = true;
            int target = template.defaultTarget();
            int pool = template.defaultRewardPool();
            if (root != null) {
                ConfigurationSection section = root.getConfigurationSection(template.id());
                if (section != null) {
                    enabled = section.getBoolean("enabled", true);
                    target = section.getInt("target", template.defaultTarget());
                    pool = section.getInt("reward-pool", template.defaultRewardPool());
                }
            }
            templates.put(template.id(), new TemplateOverride(enabled,
                    clamp(target, 1, 100_000_000), clamp(pool, 0, 1_000_000)));
        }
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (GoalTemplate.byId(key) == null) {
                    logger.warning("[CHRDK_CommonGoals] Неизвестный шаблон цели в config.yml: " + key);
                }
            }
        }
        return new GoalsConfig(requireAuth, activeCount, periodDays, bonus, Map.copyOf(templates));
    }

    public boolean requireAuth() {
        return requireAuth;
    }

    public int activeCount() {
        return activeCount;
    }

    public int periodDays() {
        return periodDays;
    }

    public int rewardBonusPercent() {
        return rewardBonusPercent;
    }

    public TemplateOverride overrideOf(String templateId) {
        return templates.get(templateId);
    }

    /** Свежие экземпляры целей для новой волны: distinct enabled-шаблоны в случайном порядке. */
    public java.util.List<GoalState> rollWave(java.util.Random random) {
        java.util.List<GoalTemplate> pool = new java.util.ArrayList<>();
        for (GoalTemplate template : GoalTemplate.ALL) {
            TemplateOverride override = overrideOf(template.id());
            if (override != null && override.enabled()) {
                pool.add(template);
            }
        }
        java.util.Collections.shuffle(pool, random);
        java.util.List<GoalState> wave = new java.util.ArrayList<>();
        int count = Math.min(activeCount, pool.size());
        for (int i = 0; i < count; i++) {
            GoalTemplate template = pool.get(i);
            TemplateOverride override = overrideOf(template.id());
            wave.add(new GoalState(template.id(), override.target(), override.rewardPool()));
        }
        return wave;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
