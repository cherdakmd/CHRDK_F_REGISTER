package ru.cherdakmd.chrdklottery;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Распарсенный config.yml лотереи. Невалидные значения заменяются дефолтами
 * с предупреждением в лог — плагин не должен падать из-за опечатки админа.
 */
public record LotteryConfig(
        boolean requirePass,
        int costDiamonds,
        int maxPerPlayer,
        int maxPerPurchase,
        DayOfWeek drawDay,
        LocalTime drawTime,
        ZoneId zone,
        List<Integer> announceMinutes,
        boolean catchUpOnStart,
        int payoutPercent
) {

    private static final DayOfWeek DEFAULT_DAY = DayOfWeek.SUNDAY;
    private static final LocalTime DEFAULT_TIME = LocalTime.of(20, 0);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static LotteryConfig parse(FileConfiguration config, Logger logger) {
        boolean requirePass = config.getBoolean("general.require-pass", false);
        int cost = Math.max(1, Math.min(4096, config.getInt("tickets.cost-diamonds", 2)));
        int maxPerPlayer = Math.max(0, Math.min(LotteryStore.MAX_TICKETS, config.getInt("tickets.max-per-player", 64)));
        int maxPerPurchase = Math.max(1, Math.min(1024, config.getInt("tickets.max-per-purchase", 32)));

        DayOfWeek day = DEFAULT_DAY;
        String rawDay = config.getString("draw.day-of-week", DEFAULT_DAY.name());
        try {
            day = DayOfWeek.valueOf(rawDay.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            warn(logger, "draw.day-of-week", rawDay, DEFAULT_DAY.name());
        }

        LocalTime time = DEFAULT_TIME;
        String rawTime = config.getString("draw.time", TIME_FORMAT.format(DEFAULT_TIME));
        try {
            time = LocalTime.parse(rawTime.trim(), TIME_FORMAT);
        } catch (RuntimeException exception) {
            warn(logger, "draw.time", rawTime, TIME_FORMAT.format(DEFAULT_TIME));
        }

        ZoneId zone = DEFAULT_ZONE;
        String rawZone = config.getString("draw.timezone", DEFAULT_ZONE.getId());
        try {
            zone = ZoneId.of(rawZone.trim());
        } catch (RuntimeException exception) {
            warn(logger, "draw.timezone", rawZone, DEFAULT_ZONE.getId());
        }

        List<Integer> announce = new ArrayList<>();
        for (int minutes : config.getIntegerList("draw.announce-minutes")) {
            if (minutes > 0 && minutes <= 24 * 60 && !announce.contains(minutes)) {
                announce.add(minutes);
            }
        }
        if (!config.contains("draw.announce-minutes")) {
            announce = List.of(30, 5, 1);
        }

        boolean catchUp = config.getBoolean("draw.catch-up-on-start", true);
        int payout = Math.max(1, Math.min(100, config.getInt("pot.payout-percent", 80)));

        return new LotteryConfig(requirePass, cost, maxPerPlayer, maxPerPurchase,
                day, time, zone, List.copyOf(announce), catchUp, payout);
    }

    private static void warn(Logger logger, String key, String raw, String fallback) {
        if (logger != null) {
            logger.warning("CHRDK_Lottery: некорректное значение " + key + "=\"" + raw
                    + "\" — используется значение по умолчанию " + fallback);
        }
    }
}
