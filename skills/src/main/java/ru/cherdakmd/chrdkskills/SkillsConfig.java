package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Разбор config.yml: кривая, опыт за действие, лимиты антинакрутки, награды
 * за вехи и обратная связь.
 *
 * <p>Опыт за действие задаётся отдельно каждому навыку — это позволяет
 * балансировать рыбалку (редкие события) против копания (частые). Все числа
 * клампятся: кривой конфиг не должен превращать прокачку в ноль или бесконечность.</p>
 */
public final class SkillsConfig {

    /** Настройки одного навыка. */
    public record SkillSettings(boolean enabled, long xpPerAction, double xpMultiplier) {
    }

    /** Награда за уровень-веху. */
    public record Milestone(int level, int diamonds) {
    }

    private final boolean enabled;
    private final boolean announce;
    private final boolean actionbar;
    private final boolean sounds;
    private final boolean countCreative;
    private final int refreshTicks;
    private final SkillCurve.Params curve;
    private final double xpRate;
    private final long maxXpPerAction;
    private final Map<SkillType, SkillSettings> skills;
    private final Map<Integer, Milestone> milestones;
    private final long placedBlockTtlSeconds;
    private final int placedBlockMaxEntries;
    private final int leaderboardSize;

    private SkillsConfig(boolean enabled, boolean announce, boolean actionbar, boolean sounds,
                         boolean countCreative, int refreshTicks, SkillCurve.Params curve,
                         double xpRate, long maxXpPerAction,
                         Map<SkillType, SkillSettings> skills,
                         Map<Integer, Milestone> milestones, long placedBlockTtlSeconds,
                         int placedBlockMaxEntries, int leaderboardSize) {
        this.enabled = enabled;
        this.announce = announce;
        this.actionbar = actionbar;
        this.sounds = sounds;
        this.countCreative = countCreative;
        this.refreshTicks = refreshTicks;
        this.curve = curve;
        this.xpRate = xpRate;
        this.maxXpPerAction = maxXpPerAction;
        this.skills = skills;
        this.milestones = milestones;
        this.placedBlockTtlSeconds = placedBlockTtlSeconds;
        this.placedBlockMaxEntries = placedBlockMaxEntries;
        this.leaderboardSize = leaderboardSize;
    }

    public static SkillsConfig parse(FileConfiguration cfg, Logger logger) {
        boolean enabled = cfg.getBoolean("general.enabled", true);
        boolean announce = cfg.getBoolean("feedback.announce", true);
        boolean actionbar = cfg.getBoolean("feedback.actionbar", true);
        boolean sounds = cfg.getBoolean("feedback.sounds", true);
        boolean countCreative = cfg.getBoolean("limits.count-creative", false);
        int refreshTicks = (int) clamp(cfg.getLong("general.refresh-ticks", 40L), 10L, 200L);
        SkillCurve.Params curve = new SkillCurve.Params(
                cfg.getInt("leveling.max-level", 100),
                cfg.getLong("leveling.base-xp", 100L),
                cfg.getDouble("leveling.xp-growth", 25.0));
        double xpRate = clamp(cfg.getDouble("leveling.xp-rate", 1.0), 0.0, 100.0);
        long maxXp = clamp(cfg.getLong("limits.max-xp-per-action", 100L), 1L, 100_000L);

        Map<SkillType, SkillSettings> skills = new EnumMap<>(SkillType.class);
        ConfigurationSection root = cfg.getConfigurationSection("skills");
        for (SkillType skill : SkillType.values()) {
            boolean skillEnabled = true;
            long xp = defaultsXp(skill);
            double multiplier = 1.0;
            if (root != null && root.isConfigurationSection(skill.id())) {
                ConfigurationSection section = root.getConfigurationSection(skill.id());
                skillEnabled = section.getBoolean("enabled", true);
                xp = clamp(section.getLong("xp", defaultsXp(skill)), 0L, maxXp);
                multiplier = clamp(section.getDouble("xp-multiplier", 1.0), 0.0, 10.0);
            }
            skills.put(skill, new SkillSettings(skillEnabled, xp, multiplier));
        }
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (SkillType.byId(key) == null && logger != null) {
                    logger.warning("[CHRDK_Skills] Неизвестный навык в config.yml: " + key);
                }
            }
        }

        Map<Integer, Milestone> milestones = new TreeMap<>();
        ConfigurationSection milestoneRoot = cfg.getConfigurationSection("milestones");
        if (milestoneRoot != null) {
            for (String key : milestoneRoot.getKeys(false)) {
                Integer level = parseInt(key);
                if (level == null || level < 1 || level > curve.maxLevel()) {
                    if (logger != null) {
                        logger.warning("[CHRDK_Skills] milestones." + key
                                + ": ключ должен быть уровнем 1.." + curve.maxLevel()
                                + " — пропускаю.");
                    }
                    continue;
                }
                int diamonds = (int) clamp(milestoneRoot.getInt(key + ".diamonds", 0), 0, 576);
                milestones.put(level, new Milestone(level, diamonds));
            }
        }
        if (milestones.isEmpty()) {
            milestones = new TreeMap<>(defaultsMilestones());
        }

        long ttl = clamp(cfg.getLong("limits.placed-block-seconds", 300L), 5L, 86_400L);
        int maxEntries = (int) clamp(cfg.getLong("limits.placed-block-max", 20_000L), 64L, 1_000_000L);
        int leaderboard = (int) clamp(cfg.getLong("limits.leaderboard-size", 10L), 3L, 50L);

        return new SkillsConfig(enabled, announce, actionbar, sounds, countCreative,
                refreshTicks, curve, xpRate, maxXp,
                skills, Collections.unmodifiableMap(milestones), ttl, maxEntries, leaderboard);
    }

    /** Значения опыта по умолчанию — те же, что в config.yml. */
    static long defaultsXp(SkillType skill) {
        return switch (skill) {
            case MINING -> 3L;
            case WOODCUTTING -> 4L;
            case DIGGING -> 2L;
            case FARMING -> 4L;
            case FISHING -> 8L;
            case COMBAT -> 2L;
            case ARCHERY -> 3L;
            case DEFENSE -> 2L;
            case ALCHEMY -> 12L;
            case ENCHANTING -> 20L;
        };
    }

    static Map<Integer, Milestone> defaultsMilestones() {
        Map<Integer, Milestone> out = new LinkedHashMap<>();
        out.put(10, new Milestone(10, 4));
        out.put(25, new Milestone(25, 8));
        out.put(50, new Milestone(50, 16));
        out.put(75, new Milestone(75, 24));
        out.put(100, new Milestone(100, 40));
        return Map.copyOf(out);
    }

    public SkillSettings skill(SkillType skill) {
        return skills.getOrDefault(skill, new SkillSettings(true, defaultsXp(skill), 1.0));
    }

    /** Итоговый опыт за одно действие с учётом множителей навыка и сервера. */
    public long xpFor(SkillType skill) {
        SkillSettings settings = skill(skill);
        if (!settings.enabled()) {
            return 0L;
        }
        long base = Math.min(maxXpPerAction, settings.xpPerAction());
        long result = Math.round(base * settings.xpMultiplier() * xpRate);
        // Множители тоже не должны пробивать потолок одного действия.
        return Math.max(0L, Math.min(maxXpPerAction, result));
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

    public boolean sounds() {
        return sounds;
    }

    /** Считать действия в креативе/спектаторе (по умолчанию — нет). */
    public boolean countCreative() {
        return countCreative;
    }

    /** Период пульса атрибутных перков в тиках. */
    public int refreshTicks() {
        return refreshTicks;
    }

    public SkillCurve.Params curve() {
        return curve;
    }

    public double xpRate() {
        return xpRate;
    }

    public long maxXpPerAction() {
        return maxXpPerAction;
    }

    public Map<Integer, Milestone> milestones() {
        return milestones;
    }

    public long placedBlockTtlSeconds() {
        return placedBlockTtlSeconds;
    }

    public int placedBlockMaxEntries() {
        return placedBlockMaxEntries;
    }

    public int leaderboardSize() {
        return leaderboardSize;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }
}
