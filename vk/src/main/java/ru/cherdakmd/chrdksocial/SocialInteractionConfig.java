package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки интерактива сообщества: callback-кнопки, диалоги, опросы, тикеты
 * и реакции стены. Отдельный снимок — привязка, доступ, донат и мост чата
 * не затрагиваются.
 *
 * <p>Всё выключено по умолчанию. Опасные действия требуют явного включения
 * {@code dialogs} (двухшаговое подтверждение) и {@code callbacks}.</p>
 */
public record SocialInteractionConfig(
        boolean enabled,
        boolean callbacks,
        boolean dialogs,
        boolean polls,
        boolean tickets,
        boolean wallReactions,
        boolean inGameDigest,
        long adminPeerId,
        int cooldownSeconds,
        int dialogTtlSeconds,
        int pollDurationSeconds,
        int maxLength,
        String pollQuestion
) {

    public static final SocialInteractionConfig DEFAULT = new SocialInteractionConfig(
            false, false, false, false, false, false, false, 0L, 15, 120, 300, 220,
            "Что улучшить на сервере в следующий раз?");

    public static SocialInteractionConfig parse(FileConfiguration cfg) {
        if (cfg == null) return DEFAULT;
        return new SocialInteractionConfig(
                cfg.getBoolean("interactive.enabled", false),
                cfg.getBoolean("interactive.callbacks", false),
                cfg.getBoolean("interactive.dialogs", false),
                cfg.getBoolean("interactive.polls", false),
                cfg.getBoolean("interactive.tickets", false),
                cfg.getBoolean("interactive.wall-reactions", false),
                cfg.getBoolean("interactive.in-game-digest", false),
                Math.max(0L, cfg.getLong("interactive.admin-peer-id", 0L)),
                Math.clamp(cfg.getInt("interactive.cooldown-seconds", 15), 0, 3600),
                Math.clamp(cfg.getInt("interactive.dialog-ttl-seconds", 120), 30, 3600),
                Math.clamp(cfg.getInt("interactive.poll-duration-seconds", 300), 30, 86_400),
                Math.clamp(cfg.getInt("interactive.max-length", 220), 40, 400),
                trimOrDefault(cfg.getString("interactive.poll-question", DEFAULT.pollQuestion()),
                        DEFAULT.pollQuestion()));
    }

    private static String trimOrDefault(String raw, String fallback) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public boolean active() {
        return enabled && (callbacks || dialogs || polls || tickets || wallReactions);
    }

    public long cooldownMillis() {
        return cooldownSeconds * 1000L;
    }

    public long dialogTtlMillis() {
        return dialogTtlSeconds * 1000L;
    }
}
