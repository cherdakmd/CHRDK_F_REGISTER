package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Пульс сторожа DonatePay: переходы «отказ → тревога → восстановление» и heartbeat. */
class DonatePulseTest {

    @Test
    void outageAlertsExactlyOncePerEpisode() {
        assertFalse(DonatePulse.shouldAlertOutage(0, 10, false));
        assertFalse(DonatePulse.shouldAlertOutage(9, 10, false));
        assertTrue(DonatePulse.shouldAlertOutage(10, 10, false),
                "порог досягнут — одна громкая тревога");
        assertFalse(DonatePulse.shouldAlertOutage(11, 10, true),
                "после тревоги молчим до восстановления — без спама");
        assertFalse(DonatePulse.shouldAlertOutage(50, 10, true));
    }

    @Test
    void recoveryOnlyAfterAlertedEpisode() {
        assertTrue(DonatePulse.shouldAlertRecovery(true));
        assertFalse(DonatePulse.shouldAlertRecovery(false),
                "мигание без тревоги не должно радостно сообщать «все в порядке»");
    }

    @Test
    void heartbeatDescribesState() {
        String flaky = DonatePulse.heartbeatLine(1_000L, 61_000L, 2, "timeout");
        assertTrue(flaky.startsWith("DonatePay heartbeat: FLAKY"), flaky);
        assertTrue(flaky.contains("60 с назад"), flaky);

        String noContact = DonatePulse.heartbeatLine(0L, 999_999L, 5, "DNS");
        assertTrue(noContact.startsWith("DonatePay heartbeat: NO CONTACT"), noContact);

        String ok = DonatePulse.heartbeatLine(90_000L, 120_000L, 0, null);
        assertTrue(ok.startsWith("DonatePay heartbeat: OK"), ok);
        assertTrue(ok.contains("30 с назад"));
    }

    @Test
    void repeatingsGateByConfigInterval() {
        assertFalse(DonatePulse.repeatDue(1_000L, 61_000L, 0), "0 минут = повторов нет");
        assertFalse(DonatePulse.repeatDue(1_000L, 61_000L, 15));
        assertTrue(DonatePulse.repeatDue(1_000L, 901_001L, 15),
                "прошло 15 минут — напомнить о лежащем сервисе");
        assertFalse(DonatePulse.repeatDue(901_000L, 902_000L, 15),
                "сразу после тревоги не дублируем");
    }

    @Test
    void classifyMirrorsHeartbeatStates() {
        assertEquals(DonatePulse.State.OK, DonatePulse.classify(1_000L, 0));
        assertEquals(DonatePulse.State.FLAKY, DonatePulse.classify(1_000L, 2));
        assertEquals(DonatePulse.State.NO_CONTACT, DonatePulse.classify(0L, 3));
        assertEquals(DonatePulse.State.OK, DonatePulse.classify(0L, 0),
                "сервер только поднялся и первого опроса не было — ничего страшного");
    }

    @Test
    void uptimeEndsAtOneHundredForFreshServer() {
        assertEquals(100, DonatePulse.uptimePercent(0, 0));
        assertEquals(100, DonatePulse.uptimePercent(10, 10));
        assertEquals(90, DonatePulse.uptimePercent(10, 9));
        assertEquals(0, DonatePulse.uptimePercent(10, 0));
    }

    @Test
    void stalePendingCountsOnlyOlderThanThreshold() {
        long now = 10_000_000L;
        long[] stamps = {now - 60_000, now - 25 * 3_600_000L, 0, now - 30 * 3_600_000L};
        assertEquals(2, DonatePulse.countStalePending(stamps, now, 24),
                "два доната старше суток, свежий и кривая метка — мимо");
        assertEquals(0, DonatePulse.countStalePending(stamps, now, 0),
                "0 часов в конфиге = напоминаний нет вовсе");
        assertEquals(3, DonatePulse.countStalePending(stamps, now, 48));
    }

    @Test
    void staleSuccessTimestampClampsAgoToZero() {
        String ok = DonatePulse.heartbeatLine(200_000L, 100_000L, 0, null);
        assertTrue(ok.contains("0 с назад"), "кривые часы не должны дать отрицательный возраст");
    }
}
