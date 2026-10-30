package ru.cherdakmd.chrdkguilds;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} гильдий (1.4.163).
 *
 * <p>Все числа зажимаются в безопасные границы: цена создания не может стать
 * отрицательной (иначе гильдии создавались бы с доплатой), а ставка войны —
 * больше казны. Опечатка в имени секции не роняет плагин: действует значение по
 * умолчанию, а в лог уходит предупреждение.</p>
 */
public final class GuildConfig {

    /** Потолок числа гильдий на сервере. */
    public static final int MAX_GUILDS_LIMIT = 2_000;
    /** Потолок ставки войны. */
    public static final long MAX_WAGER_LIMIT = 100_000L;
    /** Потолок длительности войны — сутки. */
    public static final int MAX_DURATION_SECONDS = 86_400;
    /** Потолок времени на ответ — час. */
    public static final int MAX_ACCEPT_SECONDS = 3_600;
    /** Потолок окна защиты от накрутки — час. */
    public static final int MAX_KILL_WINDOW_SECONDS = 3_600;

    private final boolean enabled;
    private final String locale;
    private final boolean actionbar;
    private final boolean chatPrefix;
    private final long creationCost;
    private final int inviteSeconds;
    private final int maxGuilds;
    private final int minName;
    private final int maxName;
    private final int minTag;
    private final int maxTag;
    private final boolean warsEnabled;
    private final long minWager;
    private final long maxWager;
    private final int warDurationSeconds;
    private final int warAcceptSeconds;
    private final int warCooldownSeconds;
    private final int killWindowSeconds;
    private final boolean claimedBonus;
    private final int maxWars;

    private GuildConfig(boolean enabled, String locale, boolean actionbar, boolean chatPrefix,
                        long creationCost, int inviteSeconds, int maxGuilds, int minName,
                        int maxName, int minTag, int maxTag, boolean warsEnabled, long minWager,
                        long maxWager, int warDurationSeconds, int warAcceptSeconds,
                        int warCooldownSeconds, int killWindowSeconds, boolean claimedBonus,
                        int maxWars) {
        this.enabled = enabled;
        this.locale = locale;
        this.actionbar = actionbar;
        this.chatPrefix = chatPrefix;
        this.creationCost = creationCost;
        this.inviteSeconds = inviteSeconds;
        this.maxGuilds = maxGuilds;
        this.minName = minName;
        this.maxName = maxName;
        this.minTag = minTag;
        this.maxTag = maxTag;
        this.warsEnabled = warsEnabled;
        this.minWager = minWager;
        this.maxWager = maxWager;
        this.warDurationSeconds = warDurationSeconds;
        this.warAcceptSeconds = warAcceptSeconds;
        this.warCooldownSeconds = warCooldownSeconds;
        this.killWindowSeconds = killWindowSeconds;
        this.claimedBonus = claimedBonus;
        this.maxWars = maxWars;
    }

    /** Умолчания: гильдии включены, войны включены, цена создания 27 алмазов. */
    public static GuildConfig defaults() {
        return new GuildConfig(true, "ru", true, true, 270, 120, 200, 3, 16, 2, 5,
                true, 320, 10_240, 3_600, 300, 3_600, 300, true, 2);
    }

    /** Разбирает конфиг; {@code null} допустим — действуют умолчания. */
    public static GuildConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;
        GuildConfig fallback = defaults();

        boolean enabled = source.getBoolean("general.enabled", fallback.enabled);
        String locale = source.getString("general.locale", fallback.locale);
        locale = locale == null || locale.isBlank()
                ? fallback.locale : locale.trim().toLowerCase(Locale.ROOT);
        boolean actionbar = source.getBoolean("general.actionbar", fallback.actionbar);
        boolean chatPrefix = source.getBoolean("general.chat-prefix", fallback.chatPrefix);
        long creationCost = clamp(source, "general.creation-cost", fallback.creationCost,
                0L, 4_096L, logger);
        int inviteSeconds = (int) clamp(source, "general.invite-seconds", fallback.inviteSeconds,
                30L, 3_600L, logger);
        int maxGuilds = (int) clamp(source, "general.max-guilds", fallback.maxGuilds,
                1L, MAX_GUILDS_LIMIT, logger);

        int minName = (int) clamp(source, "general.min-name", fallback.minName, 2L,
                Guild.MAX_NAME, logger);
        int maxName = (int) clamp(source, "general.max-name", fallback.maxName, minName,
                Guild.MAX_NAME, logger);
        int minTag = (int) clamp(source, "general.min-tag", fallback.minTag, 1L,
                Guild.MAX_TAG, logger);
        int maxTag = (int) clamp(source, "general.max-tag", fallback.maxTag, minTag,
                Guild.MAX_TAG, logger);

        boolean warsEnabled = source.getBoolean("wars.enabled", fallback.warsEnabled);
        long minWager = clamp(source, "wars.min-wager", fallback.minWager, 1L,
                MAX_WAGER_LIMIT, logger);
        long maxWager = clamp(source, "wars.max-wager", fallback.maxWager, minWager,
                MAX_WAGER_LIMIT, logger);
        int duration = (int) clamp(source, "wars.duration-seconds", fallback.warDurationSeconds,
                300L, MAX_DURATION_SECONDS, logger);
        int accept = (int) clamp(source, "wars.accept-seconds", fallback.warAcceptSeconds,
                30L, MAX_ACCEPT_SECONDS, logger);
        int cooldown = (int) clamp(source, "wars.cooldown-seconds", fallback.warCooldownSeconds,
                0L, 86_400L, logger);
        int killWindow = (int) clamp(source, "wars.kill-window-seconds",
                fallback.killWindowSeconds, 0L, MAX_KILL_WINDOW_SECONDS, logger);
        boolean claimedBonus = source.getBoolean("wars.claimed-bonus", fallback.claimedBonus);
        int maxWars = (int) clamp(source, "wars.max-active", fallback.maxWars, 1L, 16L, logger);

        return new GuildConfig(enabled, locale, actionbar, chatPrefix, creationCost,
                inviteSeconds, maxGuilds, minName, maxName, minTag, maxTag, warsEnabled,
                minWager, maxWager, duration, accept, cooldown, killWindow, claimedBonus,
                maxWars);
    }

    private static long clamp(FileConfiguration source, String path, long fallback,
                              long min, long max, Logger logger) {
        long raw = source.getLong(path, fallback);
        long value = Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Guilds] " + path + ": " + raw + " вне границ " + min
                    + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    /* --------------------------------- доступ --------------------------------- */

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean chatPrefix() {
        return chatPrefix;
    }

    public long creationCost() {
        return creationCost;
    }

    public int inviteSeconds() {
        return inviteSeconds;
    }

    public int maxGuilds() {
        return maxGuilds;
    }

    public int minName() {
        return minName;
    }

    public int maxName() {
        return maxName;
    }

    public int minTag() {
        return minTag;
    }

    public int maxTag() {
        return maxTag;
    }

    public boolean warsEnabled() {
        return warsEnabled;
    }

    public long minWager() {
        return minWager;
    }

    public long maxWager() {
        return maxWager;
    }

    public int warDurationSeconds() {
        return warDurationSeconds;
    }

    public int warAcceptSeconds() {
        return warAcceptSeconds;
    }

    public int warCooldownSeconds() {
        return warCooldownSeconds;
    }

    public int killWindowSeconds() {
        return killWindowSeconds;
    }

    public boolean claimedBonus() {
        return claimedBonus;
    }

    public int maxWars() {
        return maxWars;
    }
}
