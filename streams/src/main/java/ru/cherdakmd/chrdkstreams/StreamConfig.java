package ru.cherdakmd.chrdkstreams;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки стримерской программы ({@code streams:} в config.yml).
 *
 * <p>Разбор терпимый: битые значения зажимаются в разумные границы, а не роняют
 * плагин. Площадка без ключей авторизации просто выключается — остальные
 * продолжают работать.</p>
 *
 * @param minutesPerDay  минут эфира за одни сутки проходки
 * @param maxDaysPerDay  потолок наград за календарные сутки (UTC)
 * @param maxTotalDays   потолок наград за всё время (0 — без потолка)
 * @param pollSeconds    период опроса площадок
 * @param maxCreditMinutes максимум минут за один опрос (защита от простоя)
 * @param requireOnline  считать эфир только пока игрок на сервере
 * @param requireVerified требовать код в названии эфира
 * @param minViewers     минимум зрителей для зачёта (0 — любой)
 * @param announce       писать игроку о награде
 */
record StreamConfig(
        boolean enabled,
        int minutesPerDay,
        int maxDaysPerDay,
        int maxTotalDays,
        int pollSeconds,
        int maxCreditMinutes,
        boolean requireOnline,
        boolean requireVerified,
        int minViewers,
        boolean announce,
        Twitch twitch,
        VkPlay vkplay
) {

    static final int MIN_POLL_SECONDS = 60;
    static final int MAX_POLL_SECONDS = 600;
    static final int MIN_CREDIT_MINUTES = 1;
    static final int MAX_CREDIT_MINUTES = 240;
    static final int MAX_VIEWERS = 100_000;

    /** Twitch: приложение из dev.twitch.tv/console/apps. */
    record Twitch(boolean enabled, String clientId, String clientSecret, String token) {

        static Twitch disabled() {
            return new Twitch(false, "", "", "");
        }

        /** Хватает ли настроек, чтобы спрашивать API. */
        boolean configured() {
            return enabled && !clientId.isBlank() && (!clientSecret.isBlank() || !token.isBlank());
        }
    }

    /** VK Play Live: публичный эндпоинт канала, токен не нужен. */
    record VkPlay(boolean enabled, String apiUrl) {

        static VkPlay disabled() {
            return new VkPlay(false, VkPlayClient.DEFAULT_API_URL);
        }

        boolean configured() {
            return enabled && !apiUrl.isBlank();
        }
    }

    StreamConfig {
        minutesPerDay = Math.clamp(minutesPerDay, StreamRewards.MIN_MINUTES_PER_DAY,
                StreamRewards.MAX_MINUTES_PER_DAY);
        maxDaysPerDay = Math.clamp(maxDaysPerDay, 0, StreamRewards.MAX_DAYS_PER_DAY);
        maxTotalDays = Math.clamp(maxTotalDays, 0, StreamRewards.MAX_TOTAL_DAYS);
        pollSeconds = Math.clamp(pollSeconds, MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        maxCreditMinutes = Math.clamp(maxCreditMinutes, MIN_CREDIT_MINUTES, MAX_CREDIT_MINUTES);
        minViewers = Math.clamp(minViewers, 0, MAX_VIEWERS);
    }

    /** Правила начисления для {@link StreamRewards}. */
    StreamRewards.Rule rule() {
        return StreamRewards.Rule.of(minutesPerDay, maxDaysPerDay, maxTotalDays);
    }

    /** Максимум миллисекунд эфира, который засчитывается за один опрос. */
    long maxCreditMillis() {
        return (long) maxCreditMinutes * StreamRewards.MINUTE_MILLIS;
    }

    /** Площадки, которые реально можно опрашивать (включены и с ключами). */
    List<StreamPlatform> platforms() {
        List<StreamPlatform> result = new ArrayList<>(2);
        if (twitch.configured()) {
            result.add(StreamPlatform.TWITCH);
        }
        if (vkplay.configured()) {
            result.add(StreamPlatform.VKPLAY);
        }
        return List.copyOf(result);
    }

    /** Программа включена и есть хотя бы одна рабочая площадка. */
    boolean usable() {
        return enabled && !platforms().isEmpty();
    }

    /** Площадка доступна для новых привязок. */
    boolean available(StreamPlatform platform) {
        return platforms().contains(platform);
    }

    static StreamConfig from(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        return new StreamConfig(
                config.getBoolean("streams.enabled", true),
                config.getInt("streams.minutes-per-day", 60),
                config.getInt("streams.max-days-per-day", 6),
                config.getInt("streams.max-total-days", 365),
                config.getInt("streams.poll-seconds", 120),
                config.getInt("streams.max-credit-minutes", 10),
                config.getBoolean("streams.require-online", true),
                config.getBoolean("streams.require-verified", true),
                config.getInt("streams.min-viewers", 0),
                config.getBoolean("streams.announce", true),
                new Twitch(
                        config.getBoolean("streams.twitch.enabled", true),
                        text(config.getString("streams.twitch.client-id", "")),
                        text(config.getString("streams.twitch.client-secret", "")),
                        text(config.getString("streams.twitch.token", ""))),
                new VkPlay(
                        config.getBoolean("streams.vkplay.enabled", true),
                        apiUrl(config.getString("streams.vkplay.api-url",
                                VkPlayClient.DEFAULT_API_URL))));
    }

    /** Значения по умолчанию: обе площадки включены, но Twitch без ключей выключится сам. */
    static StreamConfig defaults() {
        return new StreamConfig(true, 60, 6, 365, 120, 10, true, true, 0, true,
                Twitch.disabled(), new VkPlay(true, VkPlayClient.DEFAULT_API_URL));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** Адрес API: только http(s) и обязательно с {@code %channel%} или путём. */
    private static String apiUrl(String raw) {
        String value = text(raw);
        if (value.isEmpty() || !(value.startsWith("https://") || value.startsWith("http://"))) {
            return VkPlayClient.DEFAULT_API_URL;
        }
        return value;
    }
}
