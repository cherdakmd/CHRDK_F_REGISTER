package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Подписи источника проходки: игрок должен видеть, за что получил дни, а не
 * «выдано администратором» на награду аддона.
 */
class GrantSourceTest {

    @Test
    void addonSourcesAreNamed() {
        assertEquals("наградой стримерской программы",
                AccessManager.describeGrantSource("streams"));
        assertEquals("наградой стримерской программы",
                AccessManager.describeGrantSource("streams:twitch"));
        assertEquals("наградой батлпасса",
                AccessManager.describeGrantSource("battlepass:mission_1"));
        assertEquals("наградой ежедневного входа",
                AccessManager.describeGrantSource("daily-rewards:day3"));
        assertEquals("бонусом за приглашённого друга",
                AccessManager.describeGrantSource("referral:Steve"));
        assertEquals("подарком", AccessManager.describeGrantSource("gift:Alex"));
        assertEquals("покупкой в магазине", AccessManager.describeGrantSource("shop:vip-queue"));
    }

    @Test
    void manualGrantStaysManual() {
        assertEquals("администратором", AccessManager.describeGrantSource("manual"));
        assertEquals("администратором", AccessManager.describeGrantSource(null));
        assertEquals("администратором", AccessManager.describeGrantSource("  "));
    }

    @Test
    void notificationTitleMentionsStreams() {
        assertEquals("Проходка продлена по стримерской программе",
                AccessManager.notificationTitle("streams"));
        assertEquals("Проходка выдана за батлпасс",
                AccessManager.notificationTitle("battlepass:claim-all"));
        assertEquals("Проходка выдана вручную", AccessManager.notificationTitle("manual"));
    }
}
