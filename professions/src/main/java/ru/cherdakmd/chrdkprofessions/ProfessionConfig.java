package ru.cherdakmd.chrdkprofessions;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} профессий (1.4.165).
 *
 * <p>Конфиг управляет темпом, а не балансом: таблицы опыта, кривая уровней и
 * потолки прибавок живут в {@link ProfessionCatalog} и {@link ProfessionMath}.
 * Иначе опечатка в YAML могла бы выдать лишние проценты скидки или отключить
 * суточный потолок незаметно для админа.</p>
 */
public final class ProfessionConfig {

    /**
     * Обязательный выбор профессии (1.4.182).
     *
     * <p>Без ремесла не идёт ни опыт, ни прибавки: игрок просто не видит
     * системы. Меню профессий поэтому открывается само и переоткрывается, пока
     * выбор не сделан, но играть это не мешает — блоки и команды не
     * блокируются.</p>
     *
     * @param required       обязателен ли выбор профессии
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

    public static final int MAX_DAILY_CAP = 100_000;
    public static final int MAX_CHOOSE_HOURS = 720;
    public static final double MIN_MULTIPLIER = 0.1;
    public static final double MAX_MULTIPLIER = 5.0;

    private final boolean enabled;
    private final String locale;
    private final boolean actionbar;
    private final boolean joinHint;
    private final boolean xpFeedback;
    private final int dailyXpCap;
    private final int chooseCooldownHours;
    private final double xpMultiplier;
    private final boolean perksEnabled;
    private final boolean perkSmithAnvil;
    private final boolean perkAlchemistBottle;
    private final boolean perkCookSaturation;
    private final boolean perkMasonBlock;
    /** 1.4.182: обязательный выбор профессии. */
    private final Onboarding onboarding;
    private final boolean masteryEnabled;

    private ProfessionConfig(boolean enabled, String locale, boolean actionbar, boolean joinHint,
                             boolean xpFeedback, int dailyXpCap, int chooseCooldownHours,
                             double xpMultiplier, boolean perksEnabled, boolean perkSmithAnvil,
                             boolean perkAlchemistBottle, boolean perkCookSaturation,
                             boolean perkMasonBlock, Onboarding onboarding, boolean masteryEnabled) {
        this.masteryEnabled = masteryEnabled;
        this.enabled = enabled;
        this.locale = locale;
        this.actionbar = actionbar;
        this.joinHint = joinHint;
        this.xpFeedback = xpFeedback;
        this.dailyXpCap = dailyXpCap;
        this.chooseCooldownHours = chooseCooldownHours;
        this.xpMultiplier = xpMultiplier;
        this.perksEnabled = perksEnabled;
        this.perkSmithAnvil = perkSmithAnvil;
        this.perkAlchemistBottle = perkAlchemistBottle;
        this.perkCookSaturation = perkCookSaturation;
        this.perkMasonBlock = perkMasonBlock;
        this.onboarding = onboarding == null ? Onboarding.DEFAULT : onboarding;
    }

    /** Умолчания — те же, что в ресурсном config.yml. */
    public static ProfessionConfig defaults() {
        return new ProfessionConfig(true, "ru", true, true, true, 1200, 24, 1.0,
                true, true, true, true, true, Onboarding.DEFAULT, true);
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static ProfessionConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;
        ProfessionConfig fallback = defaults();
        String locale = source.getString("general.locale", fallback.locale);
        locale = locale == null || locale.isBlank()
                ? fallback.locale : locale.trim().toLowerCase(Locale.ROOT);
        double multiplier = source.getDouble("general.xp-multiplier", fallback.xpMultiplier);
        if (!Double.isFinite(multiplier)) {
            multiplier = fallback.xpMultiplier;
        }
        double clamped = Math.clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        if (clamped != multiplier && logger != null) {
            logger.warning("[CHRDK_Professions] general.xp-multiplier: " + multiplier
                    + " вне границ " + MIN_MULTIPLIER + ".." + MAX_MULTIPLIER
                    + " — взято " + clamped + ".");
        }
        return new ProfessionConfig(
                source.getBoolean("general.enabled", fallback.enabled),
                locale,
                source.getBoolean("general.actionbar", fallback.actionbar),
                source.getBoolean("general.join-hint", fallback.joinHint),
                source.getBoolean("general.xp-feedback", fallback.xpFeedback),
                (int) clamp(source, "general.daily-xp-cap", fallback.dailyXpCap,
                        0, MAX_DAILY_CAP, logger),
                (int) clamp(source, "general.choose-cooldown-hours", fallback.chooseCooldownHours,
                        0, MAX_CHOOSE_HOURS, logger),
                clamped,
                source.getBoolean("perks.enabled", fallback.perksEnabled),
                source.getBoolean("perks.smith-anvil-discount", fallback.perkSmithAnvil),
                source.getBoolean("perks.alchemist-extra-bottle", fallback.perkAlchemistBottle),
                source.getBoolean("perks.cook-saturation", fallback.perkCookSaturation),
                source.getBoolean("perks.mason-extra-block", fallback.perkMasonBlock),
                parseOnboarding(source), source.getBoolean("mastery.enabled", true));
    }

    /**
     * Разбор секции {@code onboarding.*} (1.4.182).
     *
     * <p>Ключей нет — выбор обязателен: обновлённый сервер тоже должен
     * познакомить новичка с ремеслом, иначе половина игроков ходит без опыта и
     * не догадывается об этом.</p>
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

    /** Обязательный выбор профессии (1.4.182). */
    public boolean masteryEnabled() { return masteryEnabled; }

    public Onboarding onboarding() {
        return onboarding;
    }

    private static long clamp(FileConfiguration source, String path, long fallback, long min,
                              long max, Logger logger) {
        long raw = source.getLong(path, fallback);
        long value = Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Professions] " + path + ": " + raw + " вне границ "
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

    public boolean joinHint() {
        return joinHint;
    }

    /** Короткая строка в хотбаре после каждого начисления опыта (1.4.177). */
    public boolean xpFeedback() {
        return xpFeedback;
    }

    /** Суточный потолок опыта одной профессии; 0 — без ограничения. */
    public int dailyXpCap() {
        return dailyXpCap;
    }

    public int chooseCooldownHours() {
        return chooseCooldownHours;
    }

    public long chooseCooldownSeconds() {
        return chooseCooldownHours * 3_600L;
    }

    public long chooseCooldownMillis() {
        return chooseCooldownSeconds() * 1_000L;
    }

    public double xpMultiplier() {
        return xpMultiplier;
    }

    public boolean perksEnabled() {
        return perksEnabled;
    }

    public boolean perkSmithAnvil() {
        return perksEnabled && perkSmithAnvil;
    }

    public boolean perkAlchemistBottle() {
        return perksEnabled && perkAlchemistBottle;
    }

    public boolean perkCookSaturation() {
        return perksEnabled && perkCookSaturation;
    }

    public boolean perkMasonBlock() {
        return perksEnabled && perkMasonBlock;
    }

    /** Включена ли прибавка этой профессии в конфиге. */
    public boolean perkEnabled(Profession profession) {
        if (!perksEnabled || profession == null) {
            return false;
        }
        return switch (profession) {
            case SMITH -> perkSmithAnvil;
            case ALCHEMIST -> perkAlchemistBottle;
            case COOK -> perkCookSaturation;
            case MASON -> perkMasonBlock;
            default -> perksEnabled;
        };
    }
}
