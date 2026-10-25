package ru.cherdakmd.chrdkstreams;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Настройки стримерской программы ({@code streams:} в config.yml).
 *
 * <p>Разбор терпимый: битые значения зажимаются в разумные границы, а не роняют
 * плагин. Площадка без ключей авторизации просто выключается — остальные
 * продолжают работать.</p>
 *
 * @param minutesPerDay  минут эфира за одни сутки ранга
 * @param maxDaysPerDay  потолок наград за календарные сутки (UTC)
 * @param maxTotalDays   потолок наград за всё время (0 — без потолка)
 * @param pollSeconds    период опроса площадок
 * @param maxCreditMinutes максимум минут за один опрос (защита от простоя)
 * @param requireOnline  считать эфир только пока игрок на сервере
 * @param requireVerified требовать код в названии эфира
 * @param verifyOnPoll   подтверждать канал автоматически, как только код появился в эфире
 * @param minViewers     минимум зрителей для зачёта (0 — любой)
 * @param maxChannels    сколько каналов может привязать стример (1..число площадок: по одному на площадку)
 * @param checkCooldownSeconds пауза между ручными проверками канала (0 — без паузы)
 * @param statusCacheSeconds сколько секунд /stream status доверяет последнему опросу
 * @param announce       писать игроку о награде
 * @param tags           оформление ника стримера
 * @param viewers        награды зрителям за просмотр
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
        boolean verifyOnPoll,
        int minViewers,
        int maxChannels,
        int checkCooldownSeconds,
        int statusCacheSeconds,
        boolean announce,
        Twitch twitch,
        VkPlay vkplay,
        Tags tags,
        Viewers viewers
) {

    static final int MIN_POLL_SECONDS = 60;
    static final int MAX_POLL_SECONDS = 600;
    static final int MIN_CREDIT_MINUTES = 1;
    static final int MAX_CREDIT_MINUTES = 240;
    static final int MAX_VIEWERS = 100_000;
    static final int MAX_CHECK_COOLDOWN_SECONDS = 600;
    static final int MAX_STATUS_CACHE_SECONDS = 3600;
    /** Больше площадок, чем есть, привязать нельзя: у площадки один канал. */
    static final int MAX_CHANNELS = StreamPlatform.values().length;
    static final int MAX_TAG_LENGTH = 16;

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

    /**
     * Метка стримера: жирный ник и «[СТРИМ]» после него.
     *
     * <p>В чате метка статичная (сообщение нельзя «мигать» задним числом),
     * в TAB цвета {@code color-a}/{@code color-b} чередуются каждые
     * {@code blink-ticks} — это и есть мигание.</p>
     */
    record Tags(boolean enabled, String label, String chatColor, int blinkTicks,
                String colorA, String colorB, boolean tab) {

        static final int MIN_BLINK_TICKS = 4;
        static final int MAX_BLINK_TICKS = 100;

        static Tags defaults() {
            return new Tags(true, "СТРИМ", "red", 15, "red", "dark_gray", true);
        }

        Tags {
            String safeLabel = label == null ? "" : label.replaceAll("<[^>]*>", "")
                    .replace("<", "").replace(">", "").replaceAll("[\\n\\r]", "").trim();
            if (safeLabel.isEmpty()) {
                safeLabel = "СТРИМ";
            }
            if (safeLabel.length() > MAX_TAG_LENGTH) {
                safeLabel = safeLabel.substring(0, MAX_TAG_LENGTH);
            }
            label = safeLabel;
            chatColor = colorName(chatColor, "red");
            colorA = colorName(colorA, "red");
            colorB = colorName(colorB, "dark_gray");
            blinkTicks = Math.clamp(blinkTicks, MIN_BLINK_TICKS, MAX_BLINK_TICKS);
        }
    }

    /**
     * Награды зрителям: 120 минут просмотра = сутки, не больше 1 в день и 30 всего.
     *
     * <p>Зрительская программа — это не только награда, но и противодействие
     * накрутке: короткий код легко подобрать, заявок может быть сколько угодно,
     * а панель стримера не резиновая. Поэтому здесь свои потолки:
     * {@code maxPerStreamer} (сколько зрителей можно подтвердить),
     * {@code requestTtlHours} (сколько живёт заявка),
     * {@code maxCodeAttempts}/{@code codeLockMinutes} (блокировка за перебор кода).</p>
     */
    record Viewers(boolean enabled, int minutesPerDay, int maxDaysPerDay, int maxTotalDays,
                   int codeWindowMinutes, int checkinMinMinutes, boolean requireApproval,
                   int maxPerStreamer, int requestTtlHours, int maxCodeAttempts,
                   int codeLockMinutes, boolean announceCode) {

        static final int MAX_PER_STREAMER_LIMIT = 500;
        static final int MAX_REQUEST_TTL_HOURS = 720;
        static final int MAX_CODE_ATTEMPTS_LIMIT = 20;
        static final int MAX_CODE_LOCK_MINUTES = 60;

        static Viewers defaults() {
            return new Viewers(true, 120, 1, 30, 10, 5, true, 50, 72,
                    CodeGuard.DEFAULT_MAX_ATTEMPTS, 5, true);
        }

        Viewers {
            minutesPerDay = Math.clamp(minutesPerDay, StreamRewards.MIN_MINUTES_PER_DAY,
                    StreamRewards.MAX_MINUTES_PER_DAY);
            maxDaysPerDay = Math.clamp(maxDaysPerDay, 0, StreamRewards.MAX_DAYS_PER_DAY);
            maxTotalDays = Math.clamp(maxTotalDays, 0, StreamRewards.MAX_TOTAL_DAYS);
            codeWindowMinutes = Math.clamp(codeWindowMinutes,
                    ViewerCodes.MIN_WINDOW_MINUTES, ViewerCodes.MAX_WINDOW_MINUTES);
            checkinMinMinutes = Math.clamp(checkinMinMinutes, 1, codeWindowMinutes);
            maxPerStreamer = Math.clamp(maxPerStreamer, 0, MAX_PER_STREAMER_LIMIT);
            requestTtlHours = Math.clamp(requestTtlHours, 0, MAX_REQUEST_TTL_HOURS);
            maxCodeAttempts = Math.clamp(maxCodeAttempts, 0, MAX_CODE_ATTEMPTS_LIMIT);
            codeLockMinutes = Math.clamp(codeLockMinutes, 1, MAX_CODE_LOCK_MINUTES);
        }

        /** Срок жизни неподтверждённой заявки; {@code 0} — бессрочно. */
        long requestTtlMillis() {
            return ViewerRequests.ttlMillis(requestTtlHours);
        }

        /** Длина блокировки ввода кода после перебора. */
        long codeLockMillis() {
            return codeLockMinutes * 60_000L;
        }

        /** Есть ли лимит зрителей у одного стримера. */
        boolean limitsViewers() {
            return maxPerStreamer > 0;
        }

        StreamRewards.Rule rule() {
            return StreamRewards.Rule.of(minutesPerDay, maxDaysPerDay, maxTotalDays);
        }

        long codeWindowMillis() {
            return ViewerCodes.windowMillis(codeWindowMinutes);
        }

        long checkinMinMillis() {
            return (long) checkinMinMinutes * StreamRewards.MINUTE_MILLIS;
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
        maxChannels = Math.clamp(maxChannels, 1, MAX_CHANNELS);
        checkCooldownSeconds = Math.clamp(checkCooldownSeconds, 0, MAX_CHECK_COOLDOWN_SECONDS);
        statusCacheSeconds = Math.clamp(statusCacheSeconds, 0, MAX_STATUS_CACHE_SECONDS);
        tags = tags == null ? Tags.defaults() : tags;
        viewers = viewers == null ? Viewers.defaults() : viewers;
    }

    /** Правила начисления для {@link StreamRewards}. */
    StreamRewards.Rule rule() {
        return StreamRewards.Rule.of(minutesPerDay, maxDaysPerDay, maxTotalDays);
    }

    /** Максимум миллисекунд эфира, который засчитывается за один опрос. */
    long maxCreditMillis() {
        return (long) maxCreditMinutes * StreamRewards.MINUTE_MILLIS;
    }

    /** Пауза между ручными проверками канала в миллисекундах. */
    long checkCooldownMillis() {
        return checkCooldownSeconds * 1000L;
    }

    /** Сколько живёт запись кэша опроса. */
    long statusCacheMillis() {
        return statusCacheSeconds * 1000L;
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
                config.getBoolean("streams.verify-on-poll", true),
                config.getInt("streams.min-viewers", 0),
                config.getInt("streams.max-channels", 2),
                config.getInt("streams.check-cooldown-seconds", 30),
                config.getInt("streams.status-cache-seconds", 60),
                config.getBoolean("streams.announce", true),
                new Twitch(
                        config.getBoolean("streams.twitch.enabled", true),
                        text(config.getString("streams.twitch.client-id", "")),
                        text(config.getString("streams.twitch.client-secret", "")),
                        text(config.getString("streams.twitch.token", ""))),
                new VkPlay(
                        config.getBoolean("streams.vkplay.enabled", true),
                        apiUrl(config.getString("streams.vkplay.api-url",
                                VkPlayClient.DEFAULT_API_URL))),
                new Tags(
                        config.getBoolean("streams.tags.enabled", true),
                        text(config.getString("streams.tags.text", "СТРИМ")),
                        text(config.getString("streams.tags.chat-color", "red")),
                        config.getInt("streams.tags.blink-ticks", 15),
                        text(config.getString("streams.tags.color-a", "red")),
                        text(config.getString("streams.tags.color-b", "dark_gray")),
                        config.getBoolean("streams.tags.tab", true)),
                new Viewers(
                        config.getBoolean("streams.viewers.enabled", true),
                        config.getInt("streams.viewers.minutes-per-day", 120),
                        config.getInt("streams.viewers.max-days-per-day", 1),
                        config.getInt("streams.viewers.max-total-days", 30),
                        config.getInt("streams.viewers.code-window-minutes", 10),
                        config.getInt("streams.viewers.checkin-min-minutes", 5),
                        config.getBoolean("streams.viewers.require-approval", true),
                        config.getInt("streams.viewers.max-per-streamer", 50),
                        config.getInt("streams.viewers.request-ttl-hours", 72),
                        config.getInt("streams.viewers.max-code-attempts", 5),
                        config.getInt("streams.viewers.code-lock-minutes", 5),
                        config.getBoolean("streams.viewers.announce-code", true)));
    }

    /** Значения по умолчанию: обе площадки включены, но Twitch без ключей выключится сам. */
    static StreamConfig defaults() {
        return new StreamConfig(true, 60, 6, 365, 120, 10, true, true, true, 0, 2,
                30, 60, true,
                Twitch.disabled(), new VkPlay(true, VkPlayClient.DEFAULT_API_URL),
                Tags.defaults(), Viewers.defaults());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Имя цвета MiniMessage: без угловых скобок и строчными. Принимаем только
     * известные имена и {@code #rrggbb}: опечатка в конфиге не должна ронять
     * разбор сообщений (MiniMessage на неизвестном теге бросает исключение).
     */
    private static String colorName(String raw, String fallback) {
        String value = text(raw).replace("<", "").replace(">", "")
                .replace("/", "").toLowerCase(Locale.ROOT);
        return StreamerTags.safeColor(value, fallback);
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
