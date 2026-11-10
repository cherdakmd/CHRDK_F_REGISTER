package ru.cherdakmd.chrdkvk;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки четырёх персональных карточек дня. Пятый пост в сутки — уже
 * существующий {@code wall-digest} в 21:00; он настраивается отдельно.
 *
 * <p>Новые ключи необязательны: при отсутствии секции работают дефолты, поэтому
 * владельческий {@code config.yml} не перезаписывается. Все часы — в поясе
 * владельца, по умолчанию {@code Europe/Moscow}.</p>
 */
public record DailyCardsConfig(
        boolean enabled,
        ZoneId zone,
        boolean showValues,
        int richestHour,
        int prestigeHour,
        int minerHour,
        int hunterHour,
        int retryMinutes,
        String footer) {

    public static final String PREFIX = "wall-daily-cards.";
    /** Не более трёх попыток на карточку за сутки: сбой VK не спамит стену. */
    public static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_FOOTER = "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.";

    public static final DailyCardsConfig DEFAULT = new DailyCardsConfig(
            true, ZoneId.of("Europe/Moscow"), true,
            DailyCardKind.RICHEST.defaultHour(), DailyCardKind.PRESTIGE.defaultHour(),
            DailyCardKind.MINER.defaultHour(), DailyCardKind.HUNTER.defaultHour(),
            5, "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.");

    public DailyCardsConfig {
        richestHour = Math.clamp(richestHour, 0, 23);
        prestigeHour = Math.clamp(prestigeHour, 0, 23);
        minerHour = Math.clamp(minerHour, 0, 23);
        hunterHour = Math.clamp(hunterHour, 0, 23);
        retryMinutes = Math.clamp(retryMinutes, 1, 30);
        zone = zone == null ? ZoneId.of("Europe/Moscow") : zone;
        footer = footer == null || footer.isBlank() ? DEFAULT_FOOTER : footer.trim();
    }

    public static DailyCardsConfig parse(FileConfiguration cfg) {
        if (cfg == null) {
            return DEFAULT;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(cfg.getString(PREFIX + "timezone", "Europe/Moscow"));
        } catch (RuntimeException badZone) {
            zone = ZoneId.of("Europe/Moscow");
        }
        return new DailyCardsConfig(
                cfg.getBoolean(PREFIX + "enabled", DEFAULT.enabled()),
                zone,
                cfg.getBoolean(PREFIX + "show-values", DEFAULT.showValues()),
                cfg.getInt(PREFIX + DailyCardKind.RICHEST.hourKey(), DEFAULT.richestHour()),
                cfg.getInt(PREFIX + DailyCardKind.PRESTIGE.hourKey(), DEFAULT.prestigeHour()),
                cfg.getInt(PREFIX + DailyCardKind.MINER.hourKey(), DEFAULT.minerHour()),
                cfg.getInt(PREFIX + DailyCardKind.HUNTER.hourKey(), DEFAULT.hunterHour()),
                cfg.getInt(PREFIX + "retry-minutes", DEFAULT.retryMinutes()),
                cfg.getString(PREFIX + "footer", DEFAULT.footer()));
    }

    /** Ключ игровых суток в поясе владельца ({@code YYYY-MM-DD}). */
    public String dayKey(Instant instant) {
        return instant.atZone(zone).toLocalDate().toString();
    }

    public int hour(DailyCardKind kind) {
        return switch (kind) {
            case RICHEST -> richestHour;
            case PRESTIGE -> prestigeHour;
            case MINER -> minerHour;
            case HUNTER -> hunterHour;
        };
    }

    /** Назначенная минута: ровно начало часа. */
    public boolean isSlotTime(DailyCardKind kind, Instant instant) {
        var local = instant.atZone(zone);
        return local.getHour() == hour(kind) && local.getMinute() == 0;
    }

    /** Окно повторов сразу после назначенного часа. */
    public boolean isRetryTime(DailyCardKind kind, Instant instant) {
        var local = instant.atZone(zone);
        int now = local.getHour() * 60 + local.getMinute();
        int slot = hour(kind) * 60;
        return now > slot && now <= slot + retryMinutes;
    }

    public String scheduleLabel() {
        return "%02d:00 / %02d:00 / %02d:00 / %02d:00 + дайджест 21:00 %s".formatted(
                richestHour, prestigeHour, minerHour, hunterHour, zone.getId());
    }
}
