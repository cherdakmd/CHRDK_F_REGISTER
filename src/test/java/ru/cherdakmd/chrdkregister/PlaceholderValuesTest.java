package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Плейсхолдеры PlaceholderAPI (1.4.1): чистые функции форматирования значений
 * %chrdk_pass_*%, %chrdk_auth_stage%, %chrdk_chat_channel%, %chrdk_muted% и «да»/«нет».
 */
class PlaceholderValuesTest {

    private static final long NOW = 10_000_000_000L;

    private static RegistrationStore.PlayerAccount accountWithPass(long passUntil) {
        RegistrationStore.PlayerAccount account = new RegistrationStore.PlayerAccount();
        account.passUntil = passUntil;
        return account;
    }

    @Test
    void passStatusCoversMissingActiveAndExpiredPass() {
        assertEquals("нет ранга", PlaceholderValues.rankStatus(null, NOW));
        assertEquals("нет ранга", PlaceholderValues.rankStatus(accountWithPass(0), NOW));
        assertEquals("нет ранга", PlaceholderValues.rankStatus(accountWithPass(-1), NOW));
        assertEquals("активен", PlaceholderValues.rankStatus(accountWithPass(NOW + 1), NOW));
        assertEquals("активен", PlaceholderValues.passStatus(accountWithPass(NOW + 1), NOW));
        assertEquals("нет ранга", PlaceholderValues.passStatus(accountWithPass(NOW - 1), NOW), "истёкшая дата без ступени — ранга нет");
    }

    @Test
    void wholeDaysLeftRoundsPartialDayUp() {
        long day = TimeUnit.DAYS.toMillis(1);
        assertEquals(0, PlaceholderValues.wholeDaysLeft(0, NOW));
        assertEquals(0, PlaceholderValues.wholeDaysLeft(NOW, NOW));
        assertEquals(1, PlaceholderValues.wholeDaysLeft(NOW + 1, NOW), "неполный день считается за целый");
        assertEquals(1, PlaceholderValues.wholeDaysLeft(NOW + day, NOW));
        assertEquals(2, PlaceholderValues.wholeDaysLeft(NOW + day + 1, NOW));
        assertEquals(30, PlaceholderValues.wholeDaysLeft(NOW + 30 * day, NOW));
    }

    @Test
    void wholeHoursLeftRoundsPartialHourUp() {
        long hour = TimeUnit.HOURS.toMillis(1);
        assertEquals(0, PlaceholderValues.wholeHoursLeft(NOW - 1, NOW));
        assertEquals(1, PlaceholderValues.wholeHoursLeft(NOW + 1, NOW));
        assertEquals(24, PlaceholderValues.wholeHoursLeft(NOW + TimeUnit.DAYS.toMillis(1), NOW));
        assertEquals(25, PlaceholderValues.wholeHoursLeft(NOW + 24 * hour + 1, NOW));
    }

    @Test
    void passLeftDescribesRemainingTime() {
        assertEquals("нет ранга", PlaceholderValues.passLeft(null, NOW));
        assertEquals("нет ранга", PlaceholderValues.passLeft(accountWithPass(NOW - 1), NOW));
        assertEquals("2 дн. 0 ч.", PlaceholderValues.passLeft(accountWithPass(NOW + TimeUnit.DAYS.toMillis(2)), NOW));
        RegistrationStore.PlayerAccount permanent = accountWithPass(0);
        permanent.rankLevel = 3;
        permanent.rankPermanent = true;
        assertEquals("бессрочно", PlaceholderValues.passLeft(permanent, NOW));
    }

    @Test
    void stageIdsAndDescriptions() {
        for (AccessManager.PlayerStage stage : AccessManager.PlayerStage.values()) {
            assertEquals(stage.name().toLowerCase(java.util.Locale.ROOT), PlaceholderValues.stageId(stage));
            assertFalse(PlaceholderValues.describeStage(stage).isBlank());
        }
        assertEquals("нет ранга", PlaceholderValues.describeStage(AccessManager.PlayerStage.NO_PASS));
        assertEquals("в игре", PlaceholderValues.describeStage(AccessManager.PlayerStage.AUTHENTICATED));
    }

    @Test
    void channelDescriptions() {
        assertEquals("локальный", PlaceholderValues.describeChannel(ChatConfig.Channel.LOCAL));
        assertEquals("глобальный", PlaceholderValues.describeChannel(ChatConfig.Channel.GLOBAL));
        assertEquals("local", PlaceholderValues.channelId(ChatConfig.Channel.LOCAL));
        assertEquals("global", PlaceholderValues.channelId(ChatConfig.Channel.GLOBAL));
    }

    @Test
    void muteDetection() {
        assertFalse(PlaceholderValues.isMuted(null, NOW));
        assertFalse(PlaceholderValues.isMuted(accountWithPass(0), NOW), "без записи мута — не замьючен");

        RegistrationStore.PlayerAccount muted = accountWithPass(0);
        muted.mute = new RegistrationStore.MuteEntry();
        muted.mute.until = NOW + 60_000;
        assertTrue(PlaceholderValues.isMuted(muted, NOW));

        muted.mute.until = NOW - 1;
        assertFalse(PlaceholderValues.isMuted(muted, NOW), "истёкший мут не считается");
    }

    @Test
    void yesNo() {
        assertEquals("да", PlaceholderValues.yesNo(true));
        assertEquals("нет", PlaceholderValues.yesNo(false));
        assertEquals("нет", PlaceholderValues.NONE);
    }

    @Test
    void conversionPercentRoundsAndHandlesEmptyServer() {
        assertEquals(0, PlaceholderValues.conversionPercent(0, 0), "пустой сервер — 0%");
        assertEquals(0, PlaceholderValues.conversionPercent(0, 10));
        assertEquals(0, PlaceholderValues.conversionPercent(5, 0), "нет аккаунтов — 0%, без деления на ноль");
        assertEquals(100, PlaceholderValues.conversionPercent(10, 10));
        assertEquals(33, PlaceholderValues.conversionPercent(1, 3), "1 из 3 → 33%");
        assertEquals(67, PlaceholderValues.conversionPercent(2, 3), "2 из 3 → 67%");
    }
}
