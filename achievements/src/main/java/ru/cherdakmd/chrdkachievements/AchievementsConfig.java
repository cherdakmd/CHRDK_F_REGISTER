package ru.cherdakmd.chrdkachievements;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор config.yml: включённость, учёт «честной игры», бейджи и точечные
 * переопределения наград.
 *
 * <p>Награды в каталоге — значения по умолчанию; секция
 * {@code rewards.<ид>.pass-days|diamonds} позволяет администратору подкрутить
 * приз без пересборки. Значения клампятся, неизвестные иды пропускаются с
 * предупреждением.</p>
 */
public final class AchievementsConfig {

    /** Переопределение награды достижения. */
    public record Reward(int passDays, int diamonds) {
    }

    private final boolean enabled;
    private final boolean announce;
    private final boolean actionbar;
    private final boolean countCreative;
    private final boolean countFlying;
    private final boolean badges;
    private final int timeTickSeconds;
    private final Map<String, Reward> rewards;

    private AchievementsConfig(boolean enabled, boolean announce, boolean actionbar,
                               boolean countCreative, boolean countFlying, boolean badges,
                               int timeTickSeconds, Map<String, Reward> rewards) {
        this.enabled = enabled;
        this.announce = announce;
        this.actionbar = actionbar;
        this.countCreative = countCreative;
        this.countFlying = countFlying;
        this.badges = badges;
        this.timeTickSeconds = timeTickSeconds;
        this.rewards = rewards;
    }

    public static AchievementsConfig parse(FileConfiguration cfg, Logger logger) {
        boolean enabled = cfg.getBoolean("achievements.enabled", true);
        boolean announce = cfg.getBoolean("achievements.announce", true);
        boolean actionbar = cfg.getBoolean("achievements.actionbar", true);
        boolean countCreative = cfg.getBoolean("achievements.count-creative", false);
        boolean countFlying = cfg.getBoolean("achievements.count-flying", false);
        boolean badges = cfg.getBoolean("badges.enabled", true);
        int tick = clamp(cfg.getInt("achievements.playtime-tick-seconds", 60), 10, 600);
        Map<String, Reward> rewards = new HashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("rewards");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (AchievementRegistry.byId(key) == null) {
                    logger.warning("[CHRDK_Achievements] Награда для неизвестного достижения: "
                            + key);
                    continue;
                }
                // Отсутствие поля — «как в каталоге» (-1), кривое значение — кламп.
                boolean hasDays = root.contains(key + ".pass-days");
                boolean hasDiamonds = root.contains(key + ".diamonds");
                int days = hasDays ? clamp(root.getInt(key + ".pass-days"), 0, 365) : -1;
                int diamonds = hasDiamonds ? clamp(root.getInt(key + ".diamonds"), 0, 576) : -1;
                rewards.put(key, new Reward(days, diamonds));
            }
        }
        return new AchievementsConfig(enabled, announce, actionbar, countCreative, countFlying,
                badges, tick, Map.copyOf(rewards));
    }

    /** Награда с учётом переопределений: {@code -1} означает «как в каталоге». */
    public Reward rewardOf(AchievementDef def) {
        Reward override = rewards.get(def.id());
        int days = override == null || override.passDays() < 0 ? def.passDays() : override.passDays();
        int diamonds = override == null || override.diamonds() < 0
                ? def.diamonds() : override.diamonds();
        return new Reward(days, diamonds);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean announce() {
        return announce;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean countCreative() {
        return countCreative;
    }

    public boolean countFlying() {
        return countFlying;
    }

    public boolean badges() {
        return badges;
    }

    public int timeTickSeconds() {
        return timeTickSeconds;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
