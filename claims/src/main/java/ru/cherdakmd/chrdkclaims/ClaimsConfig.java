package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Разбор config.yml: экономика, налог, лимиты, миры, флаги защиты. */
public final class ClaimsConfig {

    /** Единый формат дат в сообщениях (пояс задаётся general.timezone). */
    public static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public final boolean requireAuth;
    public final int chunkPrice;
    public final int rentalMinPerDay;
    public final int sellbackBaseCap;
    public final int upkeepPerDay;
    public final int graceDays;
    public final int sellbackPercent;
    public final int saleTaxPercent;
    public final int baseChunks;
    public final int passBonusChunks;
    /** Лимит чанков по ступени ранга: индекс 0 — без ранга (1.4.167). Пусто — старое правило. */
    public final List<Integer> rankChunks;
    public final int membersPerChunk;
    public final int withdrawMax;
    public final int spawnMargin;

    /** Часовой пояс дат из general.timezone (пусто — пояс сервера). */
    public final String timezone;
    public final ZoneId zone;
    public final int mapRadius;
    /** Сколько секунд держится подсветка границ /claim borders. */
    public final int borderSeconds;
    /** Автопоказ полного контура при входе в свою территорию. */
    public final boolean borderOnOwnEntry;
    public final boolean notifyRent;
    /** Канал уведомлений о границах приватов. */
    public enum NoticeMode {
        ACTIONBAR,
        CHAT,
        BOTH;

        /** Сообщение в чат (в режиме BOTH — тоже). */
        public boolean chat() {
            return this == CHAT || this == BOTH;
        }

        /** Строка действия над хотбаром. */
        public boolean actionBar() {
            return this == ACTIONBAR || this == BOTH;
        }

        /** Неизвестное значение — «и туда, и туда»: сообщение не должно теряться. */
        public static NoticeMode parse(String raw, Logger logger) {
            if (raw == null || raw.isBlank()) {
                return BOTH;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                if (logger != null) {
                    logger.warning("[CHRDK_Claims] general.notice-mode='" + raw
                            + "' не распознан (actionbar|chat|both) — используется both.");
                }
                return BOTH;
            }
        }
    }

    /** Показывать уведомление при входе на приват (свой и чужой). */
    public final boolean enterNotice;
    /** Показывать уведомление при выходе из привата. */
    public final boolean leaveNotice;
    /** Показывать уведомления на собственных приватах (дата оплаты). */
    public final boolean noticeOwn;
    /** Показывать уведомления участникам приватов (роль в тексте). */
    public final boolean noticeMembers;
    /** Кулдаун повтора уведомления об одном чанке, секунд (0 — без ограничения). */
    public final int noticeCooldown;
    public final NoticeMode noticeMode;
    /** Вести журнал операций claims-audit.log. */
    public final boolean auditLog;

    /** Графическое меню приватов /claim menu. */
    public final boolean menuEnabled;
    /** Показывать в меню вкладку чанков, выставленных на продажу. */
    public final boolean menuShowForSale;
    /** Телепорт к приватам /claim tp и по клику в меню. */
    public final boolean teleportEnabled;
    /** Сколько секунд игрок стоит на месте до телепорта (0 — сразу). */
    public final int teleportWarmup;
    public final int teleportCost;
    /** Кулдаун между телепортами, секунд (0 — без кулдауна). */
    public final int teleportCooldown;
    /** Радиус поиска безопасной площадки вокруг центра чанка, блоков. */
    public final int teleportRadius;
    public final boolean teleportCancelMove;
    public final boolean teleportCancelDamage;

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
    /** Множители цены чанка и ренты по мирам (мир в нижнем регистре). */
    private final Map<String, Double> priceMultipliers;
    private final Map<String, Double> upkeepMultipliers;

    private ClaimsConfig(FileConfiguration cfg, Logger logger) {
        requireAuth = cfg.getBoolean("general.require-auth", true);
        chunkPrice = clamped(cfg, logger, "pricing.chunk-price", 160, 1, 1_000_000);
        upkeepPerDay = clamped(cfg, logger, "pricing.upkeep-per-day", 10, 0, 100_000);
        graceDays = clamped(cfg, logger, "pricing.grace-days", 3, 0, 90);
        rentalMinPerDay = clamped(cfg, logger, "pricing.rental-min-per-day", 20, 1, 1_000_000);
        sellbackBaseCap = clamped(cfg, logger, "pricing.sellback-base-cap", 16, 1, 1_000_000);
        sellbackPercent = clamped(cfg, logger, "pricing.sellback-percent", 50, 0, 100);
        saleTaxPercent = clamped(cfg, logger, "taxes.sale-tax-percent", 15, 0, 90);
        baseChunks = clamped(cfg, logger, "limits.base-chunks", 24, 1, 100_000);
        passBonusChunks = clamped(cfg, logger, "limits.pass-bonus-chunks", 8, 0, 100_000);
        rankChunks = rankList(cfg, "limits.rank-chunks", logger);
        membersPerChunk = clamped(cfg, logger, "limits.members-per-chunk", 8, 0, 128);
        withdrawMax = clamped(cfg, logger, "limits.withdraw-max", 2304, 1, 1_000_000);
        spawnMargin = clamped(cfg, logger, "worlds.spawn-margin", 64, 0, 100_000);
        mapRadius = clamped(cfg, logger, "general.map-radius", ClaimMap.RADIUS,
                ClaimMap.MIN_RADIUS, ClaimMap.MAX_RADIUS);
        borderSeconds = clamped(cfg, logger, "general.border-seconds", 10, 3, 60);
        borderOnOwnEntry = cfg.getBoolean("general.border-on-own-entry", true);
        enterNotice = cfg.getBoolean("general.enter-notice", true);
        leaveNotice = cfg.getBoolean("general.leave-notice", true);
        noticeOwn = cfg.getBoolean("general.notice-own", true);
        noticeMembers = cfg.getBoolean("general.notice-members", true);
        noticeCooldown = clamped(cfg, logger, "general.notice-cooldown-seconds", 2, 0, 60);
        noticeMode = NoticeMode.parse(cfg.getString("general.notice-mode", "both"), logger);
        auditLog = cfg.getBoolean("general.audit-log", true);
        notifyRent = cfg.getBoolean("general.notify-rent", true);

        menuEnabled = cfg.getBoolean("menu.enabled", true);
        menuShowForSale = cfg.getBoolean("menu.show-for-sale", true);
        teleportCost = clamped(cfg,logger,"teleport.cost-diamonds",20,10,1_000_000);
        teleportEnabled = cfg.getBoolean("teleport.enabled", true);
        teleportWarmup = clamped(cfg, logger, "teleport.warmup-seconds", 5, 0, 30);
        teleportCooldown = clamped(cfg, logger, "teleport.cooldown-seconds", 10, 0, 600);
        teleportRadius = clamped(cfg, logger, "teleport.search-radius", 3,
                ClaimTeleport.MIN_RADIUS, ClaimTeleport.MAX_RADIUS);
        teleportCancelMove = cfg.getBoolean("teleport.cancel-on-move", true);
        teleportCancelDamage = cfg.getBoolean("teleport.cancel-on-damage", true);

        timezone = cfg.getString("general.timezone", "").trim();
        zone = resolveZone(timezone, logger);

        enabledWorlds = worlds(cfg.getStringList("worlds.enabled"));
        disabledWorlds = worlds(cfg.getStringList("worlds.disabled"));
        priceMultipliers = multipliers(cfg, logger, "worlds.price-multiplier");
        upkeepMultipliers = multipliers(cfg, logger, "worlds.upkeep-multiplier");

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

    /** Цена чанка в конкретном мире (с множителем `worlds.price-multiplier`). */
    public int priceIn(String world) {
        return ClaimMath.scaled(chunkPrice, multiplier(priceMultipliers, world));
    }

    /** Рента за сутки в конкретном мире (с множителем `worlds.upkeep-multiplier`). */
    /** Старая земля не дорожает для серверного выкупа вместе с новой ценой покупки. */
    public int sellbackIn(String world) {
        int basis = ClaimMath.scaled(Math.min(chunkPrice, sellbackBaseCap), multiplier(priceMultipliers, world));
        return ClaimMath.sellbackReward(basis, sellbackPercent);
    }

    public int upkeepIn(String world) {
        return ClaimMath.scaled(upkeepPerDay, multiplier(upkeepMultipliers, world));
    }

    private static double multiplier(Map<String, Double> table, String world) {
        if (table.isEmpty() || world == null) {
            return 1.0;
        }
        Double value = table.get(world.toLowerCase(Locale.ROOT));
        return value == null ? 1.0 : value;
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

    /** Читает секцию «мир → множитель», зажимая значения в 0.1..10. */
    private static Map<String, Double> multipliers(FileConfiguration cfg, Logger logger, String path) {
        Map<String, Double> result = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            if (key == null || key.isBlank()) {
                continue;
            }
            double raw = section.getDouble(key, 1.0);
            double value = Math.min(10.0, Math.max(0.1, raw));
            if (Double.compare(value, raw) != 0) {
                logger.warning("[CHRDK_Claims] " + path + "." + key + "=" + raw
                        + " вне диапазона 0.1..10 — используется " + value + ".");
            }
            result.put(key.trim().toLowerCase(Locale.ROOT), value);
        }
        return result;
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

    /** Таблица лимитов по ступеням ранга: пустая — модуль работает по старым правилам. */
    private static List<Integer> rankList(FileConfiguration cfg, String path, Logger logger) {
        if (!cfg.isList(path)) {
            return List.of();
        }
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        for (Object raw : cfg.getList(path, List.of())) {
            if (raw instanceof Number number) {
                result.add(Math.max(0, number.intValue()));
            } else {
                logger.warning(path + ": значение «" + raw + "» не число — пропущено");
            }
        }
        return List.copyOf(result);
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
