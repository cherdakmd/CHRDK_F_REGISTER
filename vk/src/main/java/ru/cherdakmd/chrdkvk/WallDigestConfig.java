package ru.cherdakmd.chrdkvk;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки ежедневного дайджеста на стену сообщества ВКонтакте.
 *
 * <p>Все ключи необязательны: если в config.yml владельца секции ещё нет,
 * читаются значения {@link #DEFAULT}. Поэтому после замены JAR функция
 * поднимается сама, без ручного редактирования чужого конфига и без
 * перезаписи владельческих значений. Отключить можно одним ключом
 * {@code wall-digest.enabled: false}.</p>
 *
 * <p>На стену уходят только агрегаты: количество повышений престижа,
 * поддержек сервера, пик онлайна, число новичков, добытые блоки, поднятые
 * алмазы и победы над монстрами. Суммы, названия товаров, UUID, IP и время
 * входа не публикуются никогда. Имена лидеров по умолчанию выключены;
 * при включении уважается существующий opt-out игрока из VkStore.</p>
 */
public record WallDigestConfig(
        boolean enabled,
        int hour,
        int minute,
        ZoneId zone,
        boolean includePrestige,
        boolean includeDonations,
        boolean includeOnline,
        boolean includeBlocks,
        boolean includeDiamonds,
        boolean includeMobs,
        boolean showPlayerNames,
        int maxNames,
        int minEvents,
        int pollMinutes,
        String title,
        String footer) {

    public static final String PREFIX = "wall-digest.";

    public static final WallDigestConfig DEFAULT = new WallDigestConfig(
            true, 21, 0, ZoneId.of("Europe/Moscow"),
            true, true, true, true, true, true, false, 3, 1, 5,
            "Итоги дня", "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.");

    public WallDigestConfig {
        hour = Math.clamp(hour, 0, 23);
        minute = Math.clamp(minute, 0, 59);
        maxNames = Math.clamp(maxNames, 0, 5);
        minEvents = Math.clamp(minEvents, 0, 100);
        pollMinutes = Math.clamp(pollMinutes, 1, 60);
        zone = zone == null ? ZoneId.of("Europe/Moscow") : zone;
        title = blank(title) ? "Итоги дня" : title.trim();
        footer = blank(footer) ? "ТВОЙ МИР. ТВОЯ ИСТОРИЯ." : footer.trim();
    }

    public static WallDigestConfig parse(FileConfiguration cfg) {
        if (cfg == null) {
            return DEFAULT;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(cfg.getString(PREFIX + "timezone", "Europe/Moscow"));
        } catch (RuntimeException badZone) {
            zone = ZoneId.of("Europe/Moscow");
        }
        return new WallDigestConfig(
                cfg.getBoolean(PREFIX + "enabled", DEFAULT.enabled()),
                cfg.getInt(PREFIX + "hour", DEFAULT.hour()),
                cfg.getInt(PREFIX + "minute", DEFAULT.minute()),
                zone,
                cfg.getBoolean(PREFIX + "include-prestige", DEFAULT.includePrestige()),
                cfg.getBoolean(PREFIX + "include-donations", DEFAULT.includeDonations()),
                cfg.getBoolean(PREFIX + "include-online", DEFAULT.includeOnline()),
                cfg.getBoolean(PREFIX + "include-blocks", DEFAULT.includeBlocks()),
                cfg.getBoolean(PREFIX + "include-diamonds", DEFAULT.includeDiamonds()),
                cfg.getBoolean(PREFIX + "include-mobs", DEFAULT.includeMobs()),
                cfg.getBoolean(PREFIX + "show-player-names", DEFAULT.showPlayerNames()),
                cfg.getInt(PREFIX + "max-names", DEFAULT.maxNames()),
                cfg.getInt(PREFIX + "min-events", DEFAULT.minEvents()),
                cfg.getInt(PREFIX + "poll-minutes", DEFAULT.pollMinutes()),
                cfg.getString(PREFIX + "title", DEFAULT.title()),
                cfg.getString(PREFIX + "footer", DEFAULT.footer()));
    }

    /** Ключ игровых суток в часовом поясе владельца ({@code YYYY-MM-DD}). */
    public String dayKey(Instant instant) {
        return instant.atZone(zone).toLocalDate().toString();
    }

    /** Час и минута публикации в часовом поясе владельца. */
    public boolean isPostTime(Instant instant) {
        var local = instant.atZone(zone);
        return local.getHour() == hour && local.getMinute() == minute;
    }

    /**
     * Окно повторов: пять минут после назначенного времени. Нужно, чтобы
     * временный сбой VK не стоил серверу целого дайджеста, но попытки всё
     * равно ограничены {@link WallDigestCollector#MAX_ATTEMPTS}.
     */
    public boolean isRetryTime(Instant instant) {
        var local = instant.atZone(zone);
        if (local.getHour() != hour) {
            return false;
        }
        int current = local.getMinute();
        return current >= minute && current < Math.min(60, minute + 5);
    }

    public String scheduleLabel() {
        return String.format("%02d:%02d %s", hour, minute, zone.getId());
    }

    /** Удобная копия для админской настройки и тестов рендерера. */
    public WallDigestConfig withPlayerNames(boolean show) {
        return new WallDigestConfig(enabled, hour, minute, zone, includePrestige,
                includeDonations, includeOnline, includeBlocks, includeDiamonds,
                includeMobs, show, maxNames, minEvents, pollMinutes, title, footer);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
