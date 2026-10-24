package ru.cherdakmd.chrdkparty;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} групп (1.4.158).
 *
 * <p>Все числа зажимаются здесь, а не в момент использования: опечатка
 * {@code max-size: 500} не должна превратить группу в чат на весь сервер, а
 * {@code max-percent: 1000} — в бесконечный опыт. При зажиме в лог уходит
 * предупреждение с именем ключа, чтобы админ видел свою ошибку.</p>
 *
 * <p>Класс чистый: ни Bukkit-событий, ни игроков — только значения, поэтому
 * проверяется юнит-тестами без сервера.</p>
 */
public final class PartyConfig {

    /** Потолок состава: больше в одну строку чата и в меню не влезает. */
    public static final int MAX_SIZE_LIMIT = 16;
    /** Потолок бонуса: даже «мягкое» преимущество не должно ломать опыт. */
    public static final int MAX_BONUS_PERCENT = 100;
    /** Потолок радиуса: дальше 64 блоков «рядом» уже не считается. */
    public static final int MAX_RADIUS = 64;

    private final boolean enabled;
    private final String locale;
    private final int maxSize;
    private final int inviteExpireSeconds;
    private final int inviteMaxPending;
    private final boolean bonusEnabled;
    private final int bonusRadius;
    private final int perMemberPercent;
    private final int maxBonusPercent;
    private final boolean friendlyFire;

    private PartyConfig(boolean enabled, String locale, int maxSize, int inviteExpireSeconds,
                        int inviteMaxPending, boolean bonusEnabled, int bonusRadius,
                        int perMemberPercent, int maxBonusPercent, boolean friendlyFire) {
        this.enabled = enabled;
        this.locale = locale;
        this.maxSize = maxSize;
        this.inviteExpireSeconds = inviteExpireSeconds;
        this.inviteMaxPending = inviteMaxPending;
        this.bonusEnabled = bonusEnabled;
        this.bonusRadius = bonusRadius;
        this.perMemberPercent = perMemberPercent;
        this.maxBonusPercent = maxBonusPercent;
        this.friendlyFire = friendlyFire;
    }

    /** Разбор конфига; {@code null} — действуют умолчания. */
    public static PartyConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;

        boolean enabled = source.getBoolean("general.enabled", true);
        String locale = blankTo(source.getString("general.locale", "ru"), "ru")
                .toLowerCase(Locale.ROOT);

        int maxSize = clamp(source.getLong("limits.max-size", 8L), 2L, MAX_SIZE_LIMIT);
        warnClamped(logger, "limits.max-size", 8L, maxSize);
        int expire = clamp(source.getLong("limits.invite-expire-seconds", 60L), 10L, 600L);
        warnClamped(logger, "limits.invite-expire-seconds", 60L, expire);
        int pending = clamp(source.getLong("limits.invite-max-pending", 5L), 1L, 20L);
        warnClamped(logger, "limits.invite-max-pending", 5L, pending);

        boolean bonusEnabled = source.getBoolean("bonus.enabled", true);
        int radius = clamp(source.getLong("bonus.radius", 24L), 4L, MAX_RADIUS);
        warnClamped(logger, "bonus.radius", 24L, radius);
        int perMember = clamp(source.getLong("bonus.per-member-percent", 5L), 0L, 50L);
        warnClamped(logger, "bonus.per-member-percent", 5L, perMember);
        int maxPercent = clamp(source.getLong("bonus.max-percent", 15L), 0L, MAX_BONUS_PERCENT);
        warnClamped(logger, "bonus.max-percent", 15L, maxPercent);
        if (maxPercent < perMember && logger != null) {
            logger.warning("[CHRDK_Party] bonus.max-percent (" + maxPercent
                    + ") меньше bonus.per-member-percent (" + perMember
                    + ") — второй участник уже упрётся в потолок.");
        }

        boolean friendlyFire = source.getBoolean("protection.friendly-fire", false);

        return new PartyConfig(enabled, locale, maxSize, expire, pending, bonusEnabled, radius,
                perMember, maxPercent, friendlyFire);
    }

    /** Умолчания — те же, что в поставляемом {@code config.yml}. */
    public static PartyConfig defaults() {
        return new PartyConfig(true, "ru", 8, 60, 5, true, 24, 5, 15, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public int maxSize() {
        return maxSize;
    }

    public int inviteExpireSeconds() {
        return inviteExpireSeconds;
    }

    /** Срок жизни приглашения в миллисекундах. */
    public long inviteExpireMillis() {
        return inviteExpireSeconds * 1000L;
    }

    public int inviteMaxPending() {
        return inviteMaxPending;
    }

    public boolean bonusEnabled() {
        return bonusEnabled;
    }

    public int bonusRadius() {
        return bonusRadius;
    }

    public int perMemberPercent() {
        return perMemberPercent;
    }

    public int maxBonusPercent() {
        return maxBonusPercent;
    }

    public boolean friendlyFire() {
        return friendlyFire;
    }

    /* --------------------------------- служебные ------------------------------ */

    private static long clamp(long value, long min, long max) {
        return Math.clamp(value, min, max);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void warnClamped(Logger logger, String key, long fallback, long actual) {
        if (logger != null && actual != fallback) {
            logger.warning("[CHRDK_Party] " + key + "=" + actual
                    + " зажат в допустимые границы (по умолчанию " + fallback + ").");
        }
    }
}
