package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

/** Настройки блока {@code moderation:} в config.yml. Значения проверяются при чтении. */
public record ModerationConfig(
        boolean enabled,
        int warnLimit,
        String banReason,
        boolean warnsInTab,
        int journalLimit
) {
    public static final String DEFAULT_BAN_REASON =
            "%player%: %count%/%limit% активных варнов — бан по правилам сервера";

    public static final ModerationConfig DEFAULT = new ModerationConfig(
            true, ModerationLedger.DEFAULT_WARN_LIMIT, DEFAULT_BAN_REASON, true, 500);

    public static ModerationConfig fromConfig(FileConfiguration config) {
        if (config == null) return DEFAULT;
        boolean enabled = config.getBoolean("moderation.enabled", true);
        int limit = clamp(config.getInt("moderation.warn-limit", DEFAULT.warnLimit()), 1, 50);
        String reason = config.getString("moderation.ban-reason", DEFAULT.banReason());
        if (reason == null || reason.isBlank()) reason = DEFAULT.banReason();
        boolean tab = config.getBoolean("moderation.warns-in-tab", true);
        int journal = clamp(config.getInt("moderation.journal-limit", DEFAULT.journalLimit()), 10, 5000);
        return new ModerationConfig(enabled, limit, reason, tab, journal);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
