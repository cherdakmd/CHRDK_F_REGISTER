package ru.cherdakmd.chrdkvk;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки восьми карточек дня (2.0.6). Девятый пост в сутки — уже
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
        int tiersHour,
        int prestigeHour,
        int progressHour,
        int minerHour,
        int collectorHour,
        int hunterHour,
        int serverHour,
        int retryMinutes,
        String footer,
        String contact) {

    public static final String PREFIX = "wall-daily-cards.";
    /** Не более трёх попыток на карточку за сутки: сбой VK не спамит стену. */
    public static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_FOOTER = "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.";
    /** Контакт анонса сервера: адрес и минимальная версия Minecraft. */
    public static final String DEFAULT_CONTACT = "4rdk.ru-mc.ru · версия 1.21 и выше";

    public static final DailyCardsConfig DEFAULT = new DailyCardsConfig(
            true, ZoneId.of("Europe/Moscow"), true,
            DailyCardKind.RICHEST.defaultHour(), DailyCardKind.TIERS.defaultHour(),
            DailyCardKind.PRESTIGE.defaultHour(), DailyCardKind.PROGRESS.defaultHour(),
            DailyCardKind.MINER.defaultHour(), DailyCardKind.COLLECTOR.defaultHour(),
            DailyCardKind.HUNTER.defaultHour(), DailyCardKind.SERVER.defaultHour(),
            5, DEFAULT_FOOTER, DEFAULT_CONTACT);

    public DailyCardsConfig {
        richestHour = Math.clamp(richestHour, 0, 23);
        tiersHour = Math.clamp(tiersHour, 0, 23);
        prestigeHour = Math.clamp(prestigeHour, 0, 23);
        progressHour = Math.clamp(progressHour, 0, 23);
        minerHour = Math.clamp(minerHour, 0, 23);
        collectorHour = Math.clamp(collectorHour, 0, 23);
        hunterHour = Math.clamp(hunterHour, 0, 23);
        serverHour = Math.clamp(serverHour, 0, 23);
        retryMinutes = Math.clamp(retryMinutes, 1, 30);
        zone = zone == null ? ZoneId.of("Europe/Moscow") : zone;
        footer = footer == null || footer.isBlank() ? DEFAULT_FOOTER : footer.trim();
        contact = contact == null || contact.isBlank() ? DEFAULT_CONTACT : contact.trim();
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
                cfg.getInt(PREFIX + DailyCardKind.TIERS.hourKey(), DEFAULT.tiersHour()),
                cfg.getInt(PREFIX + DailyCardKind.PRESTIGE.hourKey(), DEFAULT.prestigeHour()),
                cfg.getInt(PREFIX + DailyCardKind.PROGRESS.hourKey(), DEFAULT.progressHour()),
                cfg.getInt(PREFIX + DailyCardKind.MINER.hourKey(), DEFAULT.minerHour()),
                cfg.getInt(PREFIX + DailyCardKind.COLLECTOR.hourKey(), DEFAULT.collectorHour()),
                cfg.getInt(PREFIX + DailyCardKind.HUNTER.hourKey(), DEFAULT.hunterHour()),
                cfg.getInt(PREFIX + DailyCardKind.SERVER.hourKey(), DEFAULT.serverHour()),
                cfg.getInt(PREFIX + "retry-minutes", DEFAULT.retryMinutes()),
                cfg.getString(PREFIX + "footer", DEFAULT.footer()),
                cfg.getString(PREFIX + "contact", DEFAULT.contact()));
    }

    /** Ключ игровых суток в поясе владельца ({@code YYYY-MM-DD}). */
    public String dayKey(Instant instant) {
        return instant.atZone(zone).toLocalDate().toString();
    }

    public int hour(DailyCardKind kind) {
        return switch (kind) {
            case RICHEST -> richestHour;
            case TIERS -> tiersHour;
            case PRESTIGE -> prestigeHour;
            case PROGRESS -> progressHour;
            case MINER -> minerHour;
            case COLLECTOR -> collectorHour;
            case HUNTER -> hunterHour;
            case SERVER -> serverHour;
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
        return ("%02d:00 / %02d:00 / %02d:00 / %02d:00 / %02d:00 / %02d:00 / %02d:00 / %02d:00"
                + " + дайджест 21:00 %s").formatted(
                richestHour, tiersHour, prestigeHour, progressHour,
                minerHour, collectorHour, hunterHour, serverHour, zone.getId());
    }
}
