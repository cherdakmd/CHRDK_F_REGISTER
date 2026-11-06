package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки оповещений сообщества о событиях сервера. Отдельный снимок:
 * огромные {@link SocialConfig} и {@code VkConfig} не расширяются, поэтому
 * перезагрузка событий не задевает привязку, доступ и магазин.
 *
 * <p>Всё выключено по умолчанию: новые сообщения в беседу ВКонтакте и MAX —
 * осознанный выбор владельца, а не сюрприз после обновления плагина.
 * Каналы fail-closed: MAX требует явно заданный {@code max-chat-id}, ВК —
 * уже настроенный мост беседы.</p>
 */
public record SocialEventConfig(
        boolean enabled,
        boolean playerDeath,
        boolean serverStart,
        boolean serverStop,
        boolean welcomeNewPlayer,
        boolean toVk,
        boolean toMax,
        boolean announceInGame,
        long maxChatId,
        int cooldownSeconds,
        int maxLength
) {

    public static final SocialEventConfig DEFAULT = new SocialEventConfig(
            false, false, false, false, false, true, false, false, 0L, 30, 220);

    public static SocialEventConfig parse(FileConfiguration cfg) {
        if (cfg == null) return DEFAULT;
        return new SocialEventConfig(
                cfg.getBoolean("events.enabled", false),
                cfg.getBoolean("events.player-death", false),
                cfg.getBoolean("events.server-start", false),
                cfg.getBoolean("events.server-stop", false),
                cfg.getBoolean("events.welcome-new-player", false),
                cfg.getBoolean("events.to-vk", true),
                cfg.getBoolean("events.to-max", false),
                cfg.getBoolean("events.announce-in-game", false),
                Math.max(0L, cfg.getLong("events.max-chat-id", 0L)),
                Math.clamp(cfg.getInt("events.cooldown-seconds", 30), 0, 3600),
                Math.clamp(cfg.getInt("events.max-length", 220), 40, 400));
    }

    /** Хотя бы одно событие включено и общий выключатель поднят. */
    public boolean anyEnabled() {
        return enabled && (playerDeath || serverStart || serverStop || welcomeNewPlayer);
    }

    public long cooldownMillis() {
        return cooldownSeconds * 1000L;
    }
}
