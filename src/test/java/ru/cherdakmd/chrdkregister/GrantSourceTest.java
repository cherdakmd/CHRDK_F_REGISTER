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
        assertEquals("наградой батлпасса",
                AccessManager.describeGrantSource("battlepass:mission_1"));
        assertEquals("наградой ежедневного входа",
                AccessManager.describeGrantSource("daily-rewards:day3"));
        assertEquals(AccessManager.describeGrantSource("uninstalled-addon"),
                AccessManager.describeGrantSource("referral:Steve"),
                "снятый источник больше не рекламируется как действующая награда");
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
    void notificationTitleMentionsBattlePass() {
        assertEquals("Ранг выдан за батлпасс",
                AccessManager.notificationTitle("battlepass:claim-all"));
        assertEquals("Ранг выдан вручную", AccessManager.notificationTitle("manual"));
    }
}
