package ru.cherdakmd.chrdkclasses;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} классов (1.4.164).
 *
 * <p>Конфиг управляет темпом, а не балансом: деревья и числа эффектов живут в
 * {@link ClassCatalog}. Числа зажимаются при чтении — отрицательный шаг уровней
 * или кулдаун в миллион часов не должен превращать механику в кашу.</p>
 */
public final class ClassConfig {

    /** Потолок шага «сколько уровней навыков на уровень класса». */
    public static final int MAX_LEVELS_PER_LEVEL = 100;
    /** Нижняя граница шага: мельче — уровень класса растёт быстрее скилла. */
    public static final int MIN_LEVELS_PER_LEVEL = 5;
    /** Потолок очков за уровень. */
    public static final int MAX_POINTS_PER_LEVEL = 5;
    /** Потолок стартовых очков. */
    public static final int MAX_STARTING_POINTS = 10;
    /** Потолок кулдауна смены класса в днях. */
    public static final int MAX_CHANGE_DAYS = 365;
    /** Потолок кулдауна пересборки в часах. */
    public static final int MAX_RESPEC_HOURS = 720;
    /** Потолок интервала пульса в секундах. */
    public static final int MAX_PULSE_SECONDS = 60;
    /** Потолок «вне боя» в секундах. */
    public static final int MAX_OUT_OF_COMBAT_SECONDS = 60;
    /** Потолок «сколько достижений за одно очко»: чаще одного за достижение — нельзя. */
    public static final int MAX_ACHIEVEMENT_EVERY = 100;
    /**
     * Потолок очков за достижения: дерево целиком стоит 36, а на 34-м уровне
     * доступно 34 — два очка и есть «добрать дерево до конца». Больше некуда
     * тратить, поэтому верхняя граница совпадает со смыслом.
     */
    public static final int MAX_ACHIEVEMENT_BONUS = 2;
    /** Границы множителя кулдауна умений. */
    public static final double MIN_ABILITY_MULTIPLIER = 0.5;
    public static final double MAX_ABILITY_MULTIPLIER = 3.0;

    /**
     * Обязательный выбор класса (1.4.182).
     *
     * <p>Игрок без класса не получает ничего: ни умений, ни талантов, ни
     * прибавок. Меню поэтому открывается само и переоткрывается, пока выбор не
     * сделан, — но игру это не запрещает: блоки, команды и награды остаются
     * доступны. Жёсткая блокировка намеренно не используется: она ломает
     * первый вход, если игрок отошёл от клавиатуры.</p>
     *
     * @param required       обязателен ли выбор класса
     * @param openDelayTicks задержка первого открытия после входа, тики
     * @param reopenSeconds  пауза перед повторным открытием закрытого меню, сек
     * @param remindSeconds  период напоминания в экшен-баре, сек (0 — молчать)
     */
    public record Onboarding(boolean required, int openDelayTicks, int reopenSeconds,
                             int remindSeconds) {

        /** Умолчания: включено, меню через 2 сек, переоткрытие через 10 сек. */
        public static final Onboarding DEFAULT = new Onboarding(true, 40, 10, 30);

        public Onboarding {
            openDelayTicks = Math.clamp(openDelayTicks, 0, 600);
            reopenSeconds = Math.clamp(reopenSeconds, 3, 600);
            remindSeconds = Math.clamp(remindSeconds, 0, 3600);
        }

        /** Задержка первого открытия в тиках; 0 превращается в один тик. */
        public long openTicks() {
            return Math.max(1L, openDelayTicks);
        }

        /** Пауза перед повторным открытием в тиках. */
        public long reopenTicks() {
            return reopenSeconds * 20L;
        }

        /** Период напоминания в тиках; 0 — напоминаний нет. */
        public long remindTicks() {
            return remindSeconds * 20L;
        }
    }

    private final boolean enabled;
    private final String locale;
    private final boolean actionbar;
    private final boolean effectsEnabled;
    private final int levelsPerLevel;
    private final int pointsPerLevel;
    private final int startingPoints;
    private final int changeCooldownDays;
    private final int respecCooldownHours;
    private final int pulseSeconds;
    private final int outOfCombatSeconds;
    private final boolean abilitiesEnabled;
    private final double abilityCooldownMultiplier;
    private final boolean levelNotice;
    private final boolean tagEnabled;
    private final int achievementEvery;
    private final int achievementMaxBonus;
    private final boolean ranksEnabled;
    /** 1.4.182: обязательный выбор класса. */
    private final Onboarding onboarding;

    private ClassConfig(boolean enabled, String locale, boolean actionbar, boolean effectsEnabled,
                        int levelsPerLevel, int pointsPerLevel, int startingPoints,
                        int changeCooldownDays, int respecCooldownHours, int pulseSeconds,
                        int outOfCombatSeconds, boolean abilitiesEnabled,
                        double abilityCooldownMultiplier, boolean levelNotice,
                        boolean tagEnabled, int achievementEvery, int achievementMaxBonus,
                        boolean ranksEnabled, Onboarding onboarding) {
        this.enabled = enabled;
        this.locale = locale;
        this.actionbar = actionbar;
        this.effectsEnabled = effectsEnabled;
        this.levelsPerLevel = levelsPerLevel;
        this.pointsPerLevel = pointsPerLevel;
        this.startingPoints = startingPoints;
        this.changeCooldownDays = changeCooldownDays;
        this.respecCooldownHours = respecCooldownHours;
        this.pulseSeconds = pulseSeconds;
        this.outOfCombatSeconds = outOfCombatSeconds;
        this.abilitiesEnabled = abilitiesEnabled;
        this.abilityCooldownMultiplier = abilityCooldownMultiplier;
        this.levelNotice = levelNotice;
        this.tagEnabled = tagEnabled;
        this.achievementEvery = achievementEvery;
        this.achievementMaxBonus = achievementMaxBonus;
        this.ranksEnabled = ranksEnabled;
        this.onboarding = onboarding == null ? Onboarding.DEFAULT : onboarding;
    }

    /** Умолчания — те же, что в ресурсном config.yml. */
    public static ClassConfig defaults() {
        return new ClassConfig(true, "ru", true, true, 30, 1, 1, 7, 24, 5, 8, true, 1.0, true,
                true, 15, 2, true, Onboarding.DEFAULT);
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static ClassConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;
        ClassConfig fallback = defaults();
        String locale = source.getString("general.locale", fallback.locale);
        locale = locale == null || locale.isBlank()
                ? fallback.locale : locale.trim().toLowerCase(Locale.ROOT);
        return new ClassConfig(
                source.getBoolean("general.enabled", fallback.enabled),
                locale,
                source.getBoolean("general.actionbar", fallback.actionbar),
                source.getBoolean("effects.enabled", fallback.effectsEnabled),
                (int) clamp(source, "general.levels-per-level", fallback.levelsPerLevel,
                        MIN_LEVELS_PER_LEVEL, MAX_LEVELS_PER_LEVEL, logger),
                (int) clamp(source, "general.points-per-level", fallback.pointsPerLevel,
                        0, MAX_POINTS_PER_LEVEL, logger),
                (int) clamp(source, "general.starting-points", fallback.startingPoints,
                        0, MAX_STARTING_POINTS, logger),
                (int) clamp(source, "general.change-cooldown-days", fallback.changeCooldownDays,
                        0, MAX_CHANGE_DAYS, logger),
                (int) clamp(source, "general.respec-cooldown-hours", fallback.respecCooldownHours,
                        0, MAX_RESPEC_HOURS, logger),
                (int) clamp(source, "general.pulse-seconds", fallback.pulseSeconds,
                        1, MAX_PULSE_SECONDS, logger),
                (int) clamp(source, "general.regen-out-of-combat-seconds",
                        fallback.outOfCombatSeconds, 0, MAX_OUT_OF_COMBAT_SECONDS, logger),
                source.getBoolean("abilities.enabled", fallback.abilitiesEnabled),
                clampDouble(source, "abilities.cooldown-multiplier",
                        fallback.abilityCooldownMultiplier, MIN_ABILITY_MULTIPLIER,
                        MAX_ABILITY_MULTIPLIER, logger),
                source.getBoolean("general.level-notice", fallback.levelNotice),
                source.getBoolean("tag.enabled", fallback.tagEnabled),
                (int) clamp(source, "points.achievement-every", fallback.achievementEvery,
                        0, MAX_ACHIEVEMENT_EVERY, logger),
                (int) clamp(source, "points.achievement-max", fallback.achievementMaxBonus,
                        0, MAX_ACHIEVEMENT_BONUS, logger),
                source.getBoolean("ranks.enabled", fallback.ranksEnabled),
                parseOnboarding(source));
    }

    /**
     * Разбор секции {@code onboarding.*} (1.4.182).
     *
     * <p>Ключей нет — выбор обязателен: сервер, обновившийся со старой версии,
     * тоже должен познакомить новичка с классом, иначе половина игроков ходит
     * без умений и не знает об этом.</p>
     */
    private static Onboarding parseOnboarding(FileConfiguration source) {
        Onboarding fallback = Onboarding.DEFAULT;
        return new Onboarding(
                source.getBoolean("onboarding.required", fallback.required()),
                (int) clamp(source, "onboarding.open-delay-ticks", fallback.openDelayTicks(),
                        0, 600, null),
                (int) clamp(source, "onboarding.reopen-seconds", fallback.reopenSeconds(),
                        3, 600, null),
                (int) clamp(source, "onboarding.remind-seconds", fallback.remindSeconds(),
                        0, 3600, null));
    }

    /** Обязательный выбор класса (1.4.182). */
    public Onboarding onboarding() {
        return onboarding;
    }

    private static long clamp(FileConfiguration source, String path, long fallback, long min,
                              long max, Logger logger) {
        long raw = source.getLong(path, fallback);
        long value = Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Classes] " + path + ": " + raw + " вне границ "
                    + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    private static double clampDouble(FileConfiguration source, String path, double fallback,
                                      double min, double max, Logger logger) {
        double raw = source.getDouble(path, fallback);
        double value = Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Classes] " + path + ": " + raw + " вне границ "
                    + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean effectsEnabled() {
        return effectsEnabled;
    }

    public int levelsPerLevel() {
        return levelsPerLevel;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public int changeCooldownDays() {
        return changeCooldownDays;
    }

    public long changeCooldownSeconds() {
        return changeCooldownDays * 86_400L;
    }

    public int respecCooldownHours() {
        return respecCooldownHours;
    }

    public long respecCooldownSeconds() {
        return respecCooldownHours * 3_600L;
    }

    public int pulseSeconds() {
        return pulseSeconds;
    }

    public long pulseTicks() {
        return Math.max(1, pulseSeconds) * 20L;
    }

    public int outOfCombatSeconds() {
        return outOfCombatSeconds;
    }

    public long outOfCombatMillis() {
        return outOfCombatSeconds * 1_000L;
    }

    /** Включены ли активные умения классов. */
    public boolean abilitiesEnabled() {
        return abilitiesEnabled;
    }

    /** Множитель кулдауна умений: 0.5 — вдвое чаще, 3 — втрое реже. */
    public double abilityCooldownMultiplier() {
        return abilityCooldownMultiplier;
    }

    /** Сообщать ли о повышении уровня класса. */
    public boolean levelNotice() {
        return levelNotice;
    }

    /** Показывать ли метку класса в нике (чат и TAB). */
    public boolean tagEnabled() {
        return tagEnabled;
    }

    /** Через сколько открытых достижений даётся очко таланта (0 — не давать). */
    public int achievementEvery() {
        return achievementEvery;
    }

    /** Потолок очков за достижения. */
    public int achievementMaxBonus() {
        return achievementMaxBonus;
    }

    /** Включены ли плюшки донатных рангов (пороги, откаты, пересборка). */
    public boolean ranksEnabled() {
        return ranksEnabled;
    }
}
