package ru.cherdakmd.chrdkstreams;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор streams.* из config.yml: значения по умолчанию, зажим границ, доступность площадок. */
class StreamConfigTest {

    private static StreamConfig parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return StreamConfig.from(configuration);
    }

    @Test
    void emptyConfigUsesSafeDefaults() throws Exception {
        StreamConfig config = parse("");

        assertTrue(config.enabled());
        assertEquals(60, config.minutesPerDay(), "час эфира = сутки");
        assertEquals(6, config.maxDaysPerDay());
        assertEquals(365, config.maxTotalDays());
        assertEquals(120, config.pollSeconds());
        assertEquals(10, config.maxCreditMinutes());
        assertTrue(config.requireOnline());
        assertTrue(config.requireVerified());
        assertTrue(config.verifyOnPoll(), "канал подтверждается сам, без /stream check");
        assertEquals(30, config.checkCooldownSeconds());
        assertEquals(60, config.statusCacheSeconds());
        assertEquals(0, config.minViewers());
        assertEquals(2, config.maxChannels(), "по умолчанию Twitch + VK Play Live");
        assertEquals(50, config.viewers().maxPerStreamer());
        assertEquals(72, config.viewers().requestTtlHours());
        assertEquals(72 * 3_600_000L, config.viewers().requestTtlMillis());
        assertEquals(5, config.viewers().maxCodeAttempts());
        assertEquals(5, config.viewers().codeLockMinutes());
        assertEquals(5 * 60_000L, config.viewers().codeLockMillis());
        assertTrue(config.viewers().announceCode());
        assertTrue(config.announce());
        assertEquals(10 * StreamRewards.MINUTE_MILLIS, config.maxCreditMillis());
    }

    @Test
    void brokenNumbersAreClamped() throws Exception {
        StreamConfig config = parse("""
                streams:
                  minutes-per-day: 0
                  max-days-per-day: 99
                  max-total-days: -3
                  poll-seconds: 1
                  max-credit-minutes: 0
                  min-viewers: -10
                """);

        assertEquals(1, config.minutesPerDay());
        assertEquals(StreamRewards.MAX_DAYS_PER_DAY, config.maxDaysPerDay());
        assertEquals(0, config.maxTotalDays());
        assertEquals(StreamConfig.MIN_POLL_SECONDS, config.pollSeconds());
        assertEquals(StreamConfig.MIN_CREDIT_MINUTES, config.maxCreditMinutes());
        assertEquals(0, config.minViewers());
    }

    @Test
    void maxChannelsIsClampedToOneOrTwo() throws Exception {
        assertEquals(1, parse("streams:\n  max-channels: 0\n").maxChannels());
        assertEquals(1, parse("streams:\n  max-channels: 1\n").maxChannels());
        assertEquals(2, parse("streams:\n  max-channels: 7\n").maxChannels());
        assertEquals(StreamConfig.MAX_CHANNELS, parse("").maxChannels());
    }

    @Test
    void antiFraudSettingsAreClamped() throws Exception {
        StreamConfig config = parse("""
                streams:
                  check-cooldown-seconds: 99999
                  status-cache-seconds: -10
                  viewers:
                    max-per-streamer: 100000
                    request-ttl-hours: -1
                    max-code-attempts: 999
                    code-lock-minutes: 0
                """);

        assertEquals(StreamConfig.MAX_CHECK_COOLDOWN_SECONDS, config.checkCooldownSeconds());
        assertEquals(0, config.statusCacheSeconds());
        assertEquals(StreamConfig.Viewers.MAX_PER_STREAMER_LIMIT,
                config.viewers().maxPerStreamer());
        assertEquals(0, config.viewers().requestTtlHours(), "0 — заявки не истекают");
        assertEquals(0L, config.viewers().requestTtlMillis());
        assertEquals(StreamConfig.Viewers.MAX_CODE_ATTEMPTS_LIMIT,
                config.viewers().maxCodeAttempts());
        assertEquals(1, config.viewers().codeLockMinutes(), "блокировка не может быть нулевой");
    }

    @Test
    void verificationCanBeSwitchedToManual() throws Exception {
        StreamConfig config = parse("""
                streams:
                  verify-on-poll: false
                """);

        assertFalse(config.verifyOnPoll());
    }

    @Test
    void pollIntervalHasUpperBound() throws Exception {
        StreamConfig config = parse("""
                streams:
                  poll-seconds: 99999
                """);

        assertEquals(StreamConfig.MAX_POLL_SECONDS, config.pollSeconds());
    }

    @Test
    void twitchWithoutKeysIsUnavailable() throws Exception {
        StreamConfig config = parse("""
                streams:
                  twitch:
                    enabled: true
                    client-id: ""
                    client-secret: ""
                  vkplay:
                    enabled: true
                """);

        assertFalse(config.twitch().configured());
        assertEquals(1, config.platforms().size());
        assertEquals(StreamPlatform.VKPLAY, config.platforms().get(0));
        assertTrue(config.usable());
    }

    @Test
    void twitchWithStaticTokenIsConfigured() throws Exception {
        StreamConfig config = parse("""
                streams:
                  twitch:
                    enabled: true
                    client-id: "abc"
                    token: "token"
                """);

        assertTrue(config.twitch().configured());
        assertTrue(config.platforms().contains(StreamPlatform.TWITCH));
    }

    @Test
    void disabledEverythingMeansNotUsable() throws Exception {
        StreamConfig config = parse("""
                streams:
                  enabled: false
                  vkplay:
                    enabled: false
                """);

        assertFalse(config.enabled());
        assertTrue(config.platforms().isEmpty());
        assertFalse(config.usable());
        assertFalse(config.available(StreamPlatform.VKPLAY));
    }

    @Test
    void apiUrlMustBeHttp() throws Exception {
        StreamConfig config = parse("""
                streams:
                  vkplay:
                    api-url: "ftp://example.com/%channel%"
                """);

        assertEquals(VkPlayClient.DEFAULT_API_URL, config.vkplay().apiUrl());
    }

    @Test
    void tagsDefaultToBoldBlinkingLabel() throws Exception {
        StreamConfig config = parse("");

        assertTrue(config.tags().enabled());
        assertEquals("СТРИМ", config.tags().label());
        assertEquals("red", config.tags().chatColor());
        assertEquals(15, config.tags().blinkTicks());
        assertEquals("red", config.tags().colorA());
        assertEquals("dark_gray", config.tags().colorB());
        assertTrue(config.tags().tab());
    }

    @Test
    void tagIsSanitizedAndBlinkIsClamped() throws Exception {
        StreamConfig config = parse("""
                streams:
                  tags:
                    text: "<red>В ЭФИРЕ</red>"
                    blink-ticks: 1
                    color-a: "<red>"
                    color-b: ""
                """);

        assertEquals("В ЭФИРЕ", config.tags().label());
        assertEquals(StreamConfig.Tags.MIN_BLINK_TICKS, config.tags().blinkTicks());
        assertEquals("red", config.tags().colorA());
        assertEquals("dark_gray", config.tags().colorB());
    }

    @Test
    void unknownColorFallsBackInsteadOfBreakingMessages() throws Exception {
        StreamConfig config = parse("""
                streams:
                  tags:
                    chat-color: "неон"
                    color-a: "#FF8800"
                """);

        assertEquals("red", config.tags().chatColor(), "опечатка не должна ломать разметку");
        assertEquals("#ff8800", config.tags().colorA());
    }

    @Test
    void labelLengthIsCapped() throws Exception {
        StreamConfig config = parse("""
                streams:
                  tags:
                    text: "ОЧЕНЬДЛИННОЕИМЯМЕТКИ"
                """);

        assertEquals(StreamConfig.MAX_TAG_LENGTH, config.tags().label().length());
    }

    @Test
    void viewersDefaultToTwoHoursPerPassDay() throws Exception {
        StreamConfig config = parse("");

        assertTrue(config.viewers().enabled());
        assertEquals(120, config.viewers().minutesPerDay());
        assertEquals(1, config.viewers().maxDaysPerDay());
        assertEquals(30, config.viewers().maxTotalDays());
        assertEquals(10, config.viewers().codeWindowMinutes());
        assertEquals(5, config.viewers().checkinMinMinutes());
        assertTrue(config.viewers().requireApproval());
        assertEquals(10 * StreamRewards.MINUTE_MILLIS, config.viewers().codeWindowMillis());
        assertEquals(5 * StreamRewards.MINUTE_MILLIS, config.viewers().checkinMinMillis());
        assertEquals(StreamRewards.Rule.of(120, 1, 30), config.viewers().rule());
    }

    @Test
    void viewerNumbersAreClamped() throws Exception {
        StreamConfig config = parse("""
                streams:
                  viewers:
                    minutes-per-day: 0
                    max-days-per-day: 99
                    max-total-days: 99999
                    code-window-minutes: 1
                    checkin-min-minutes: 999
                """);

        assertEquals(StreamRewards.MIN_MINUTES_PER_DAY, config.viewers().minutesPerDay());
        assertEquals(StreamRewards.MAX_DAYS_PER_DAY, config.viewers().maxDaysPerDay());
        assertEquals(StreamRewards.MAX_TOTAL_DAYS, config.viewers().maxTotalDays());
        assertEquals(ViewerCodes.MIN_WINDOW_MINUTES, config.viewers().codeWindowMinutes());
        assertEquals(ViewerCodes.MIN_WINDOW_MINUTES, config.viewers().checkinMinMinutes(),
                "чек-ин не может быть реже окна кода");
    }

    @Test
    void defaultsMatchShippedConfigKeys() {
        StreamConfig config = StreamConfig.defaults();

        assertEquals(60, config.minutesPerDay());
        assertEquals(StreamRewards.Rule.of(60, 6, 365), config.rule());
        assertEquals(StreamConfig.Tags.defaults(), config.tags());
        assertEquals(StreamConfig.Viewers.defaults(), config.viewers());
    }
}
