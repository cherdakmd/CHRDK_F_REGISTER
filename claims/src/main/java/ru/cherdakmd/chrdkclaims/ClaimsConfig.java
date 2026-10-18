package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Разбор config.yml: экономика, налог, лимиты, миры, флаги защиты. */
public final class ClaimsConfig {

    /** Единый формат дат в сообщениях (пояс задаётся general.timezone). */
    public static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public final boolean requireAuth;
    public final int chunkPrice;
    public final int upkeepPerDay;
    public final int graceDays;
    public final int sellbackPercent;
    public final int saleTaxPercent;
    public final int baseChunks;
    public final int passBonusChunks;
    public final int membersPerChunk;
    public final int withdrawMax;
    public final int spawnMargin;

    /** Часовой пояс дат из general.timezone (пусто — пояс сервера). */
    public final String timezone;
    public final ZoneId zone;
    public final int mapRadius;
    /** Сколько секунд держится подсветка границ /claim borders. */
    public final int borderSeconds;
    public final boolean notifyRent;

    public final boolean pvp;
    public final boolean fire;
    public final boolean explosions;
    public final boolean fluids;
    public final boolean pistons;
    public final boolean hoppers;
    public final boolean doors;
    public final boolean redstone;
    public final boolean workstations;
    public final boolean mobGriefing;
    public final boolean entities;

    private final Set<String> enabledWorlds;
    private final Set<String> disabledWorlds;

    private ClaimsConfig(FileConfiguration cfg, Logger logger) {
        requireAuth = cfg.getBoolean("general.require-auth", true);
        chunkPrice = clamped(cfg, logger, "pricing.chunk-price", 16, 1, 1_000_000);
        upkeepPerDay = clamped(cfg, logger, "pricing.upkeep-per-day", 1, 0, 100_000);
        graceDays = clamped(cfg, logger, "pricing.grace-days", 3, 0, 90);
        sellbackPercent = clamped(cfg, logger, "pricing.sellback-percent", 50, 0, 100);
        saleTaxPercent = clamped(cfg, logger, "taxes.sale-tax-percent", 10, 0, 90);
        baseChunks = clamped(cfg, logger, "limits.base-chunks", 24, 1, 100_000);
        passBonusChunks = clamped(cfg, logger, "limits.pass-bonus-chunks", 8, 0, 100_000);
        membersPerChunk = clamped(cfg, logger, "limits.members-per-chunk", 8, 0, 128);
        withdrawMax = clamped(cfg, logger, "limits.withdraw-max", 2304, 1, 1_000_000);
        spawnMargin = clamped(cfg, logger, "worlds.spawn-margin", 64, 0, 100_000);
        mapRadius = clamped(cfg, logger, "general.map-radius", ClaimMap.RADIUS,
                ClaimMap.MIN_RADIUS, ClaimMap.MAX_RADIUS);
        borderSeconds = clamped(cfg, logger, "general.border-seconds", 10, 3, 60);
        notifyRent = cfg.getBoolean("general.notify-rent", true);

        timezone = cfg.getString("general.timezone", "").trim();
        zone = resolveZone(timezone, logger);

        enabledWorlds = worlds(cfg.getStringList("worlds.enabled"));
        disabledWorlds = worlds(cfg.getStringList("worlds.disabled"));

        pvp = cfg.getBoolean("protect.pvp", false);
        fire = cfg.getBoolean("protect.fire", true);
        explosions = cfg.getBoolean("protect.explosions", true);
        fluids = cfg.getBoolean("protect.fluids", true);
        pistons = cfg.getBoolean("protect.pistons", true);
        hoppers = cfg.getBoolean("protect.hoppers", true);
        doors = cfg.getBoolean("protect.doors", true);
        redstone = cfg.getBoolean("protect.redstone", false);
        workstations = cfg.getBoolean("protect.workstations", true);
        mobGriefing = cfg.getBoolean("protect.mob-griefing", true);
        entities = cfg.getBoolean("protect.entities", true);
    }

    public static ClaimsConfig parse(FileConfiguration cfg, Logger logger) {
        Logger safeLogger = logger == null ? Logger.getAnonymousLogger() : logger;
        return new ClaimsConfig(cfg, safeLogger);
    }

    /**
     * Разрешены ли приваты в мире.
     *
     * <p>{@code worlds.enabled} пуст — разрешены все миры; {@code worlds.disabled}
     * всегда главнее. Значение {@code "*"} можно использовать как «все миры».</p>
     */
    public boolean worldEnabled(String world) {
        if (world == null || world.isBlank()) {
            return false;
        }
        String key = world.toLowerCase(Locale.ROOT);
        if (disabledWorlds.contains("*") || disabledWorlds.contains(key)) {
            return false;
        }
        return enabledWorlds.isEmpty() || enabledWorlds.contains("*") || enabledWorlds.contains(key);
    }

    public Set<String> enabledWorlds() {
        return Set.copyOf(enabledWorlds);
    }

    public Set<String> disabledWorlds() {
        return Set.copyOf(disabledWorlds);
    }

    /** Дата в настроенном поясе (для /claim info, /claim list, /claimadmin). */
    public String formatDate(long millis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    private static ZoneId resolveZone(String timezone, Logger logger) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            logger.warning("[CHRDK_Claims] general.timezone='" + timezone
                    + "' не распознан (" + exception.getMessage() + ") — используется пояс сервера.");
            return ZoneId.systemDefault();
        }
    }

    private static Set<String> worlds(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null) {
            return result;
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static int clamped(FileConfiguration cfg, Logger logger, String path, int fallback,
                               int min, int max) {
        int raw = cfg.getInt(path, fallback);
        int value = Math.min(max, Math.max(min, raw));
        if (value != raw) {
            logger.warning("[CHRDK_Claims] " + path + "=" + raw + " вне диапазона "
                    + min + ".." + max + " — используется " + value + ".");
        }
        return value;
    }
}
