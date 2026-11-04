package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Настройки блока {@code moderation:} в config.yml. Значения проверяются при чтении. */
public record ModerationConfig(
        boolean enabled,
        int warnLimit,
        String banReason,
        boolean warnsInTab,
        int journalLimit,
        List<Long> mutePresets
) {
    public static final String DEFAULT_BAN_REASON =
            "%player%: %count%/%limit% активных варнов — бан по правилам сервера";

    /** 10 минут, 1 час, 1 день, 7 дней — кнопки мута в GUI. */
    public static final List<Long> DEFAULT_MUTE_PRESETS = List.of(10L, 60L, 1_440L, 10_080L);

    public static final ModerationConfig DEFAULT = new ModerationConfig(
            true, ModerationLedger.DEFAULT_WARN_LIMIT, DEFAULT_BAN_REASON, true, 500,
            DEFAULT_MUTE_PRESETS);

    public static ModerationConfig fromConfig(FileConfiguration config) {
        if (config == null) return DEFAULT;
        boolean enabled = config.getBoolean("moderation.enabled", true);
        int limit = clamp(config.getInt("moderation.warn-limit", DEFAULT.warnLimit()), 1, 50);
        String reason = config.getString("moderation.ban-reason", DEFAULT.banReason());
        if (reason == null || reason.isBlank()) reason = DEFAULT.banReason();
        boolean tab = config.getBoolean("moderation.warns-in-tab", true);
        int journal = clamp(config.getInt("moderation.journal-limit", DEFAULT.journalLimit()), 10, 5000);
        return new ModerationConfig(enabled, limit, reason, tab, journal, mutePresets(config));
    }

    /** Пустой/мусорный список в конфиге не оставляет панель без кнопок мута. */
    private static List<Long> mutePresets(FileConfiguration config) {
        List<Long> parsed = new ArrayList<>();
        for (long value : config.getLongList("moderation.mute-presets-minutes")) {
            if (value > 0 && value <= 525_600 && parsed.size() < 5) parsed.add(value);
        }
        if (parsed.isEmpty()) {
            // Альтернатива для админов, которые записали кнопки строками: ["10", "60"].
            for (String raw : config.getStringList("moderation.mute-presets-minutes")) {
                try {
                    long minutes = Long.parseLong(raw.trim());
                    if (minutes > 0 && minutes <= 525_600 && parsed.size() < 5) parsed.add(minutes);
                } catch (NumberFormatException ignored) {
                    // Нечисловую кнопку просто не показываем.
                }
            }
        }
        return parsed.isEmpty() ? DEFAULT_MUTE_PRESETS : List.copyOf(parsed);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
