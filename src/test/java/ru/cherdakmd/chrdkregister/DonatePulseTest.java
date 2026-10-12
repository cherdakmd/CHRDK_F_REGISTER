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
    void staleSuccessTimestampClampsAgoToZero() {
        String ok = DonatePulse.heartbeatLine(200_000L, 100_000L, 0, null);
        assertTrue(ok.contains("0 с назад"), "кривые часы не должны дать отрицательный возраст");
    }
}
