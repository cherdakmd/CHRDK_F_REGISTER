package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ApiAuthStage;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.events.PassExpiredEvent;
import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;
import ru.cherdakmd.chrdkregister.api.events.PassRevokedEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API для аддонов (1.4.6): стабильность контракта — константы enum не удаляются,
 * события несут нужные данные, узел LuckPerms собирается предсказуемо.
 */
class AddonApiContractTest {

    @Test
    void apiVersionIsPositive() {
        assertTrue(ChrdkRegisterApi.API_VERSION >= 1,
                "версия API должна расти при расширении контракта");
    }

    @Test
    void addonNotificationContractIsAdditive() throws Exception {
        assertTrue(ChrdkRegisterApi.API_VERSION >= 2);
        assertTrue(ChrdkRegisterApi.class.getMethod("notifyEvent", String.class, String.class,
                String.class, String.class).isDefault());
    }

    @Test
    void shopEntitlementsAreAdditive() throws Exception {
        assertTrue(ChrdkRegisterApi.API_VERSION >= 5);
        assertTrue(ChrdkRegisterApi.class.getMethod("hasEntitlement", String.class, String.class)
                .isDefault(), "старые реализации API не должны ломаться");
        assertTrue(ChrdkRegisterApi.class.getMethod("entitlementUntil", String.class, String.class)
                .isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("grantEntitlement", String.class, String.class,
                int.class, String.class).isDefault());
    }

    @Test
    void authStagesKeepStableOrder() {
        // Аддоны полагаются на то, что значения не переименовываются/не удаляются.
        assertEquals("NO_PASS", ApiAuthStage.values()[0].name());
        assertEquals("AUTHENTICATED",
                ApiAuthStage.values()[ApiAuthStage.values().length - 1].name());
    }

    @Test
    void passGrantedEventCarriesPayload() {
        PassGrantedEvent event = new PassGrantedEvent("Steve", 1_700_000_000_000L, 30, "donatepay:#42");
        assertEquals("Steve", event.getPlayerName());
        assertEquals(1_700_000_000_000L, event.getPassUntil());
        assertEquals(30, event.getDays());
        assertEquals("donatepay:#42", event.getSource());
        assertNotNull(PassGrantedEvent.getHandlerList());
    }

    @Test
    void passRevokedAndExpiredEventsCarryName() {
        assertEquals("Alex", new PassRevokedEvent("Alex").getPlayerName());
        assertEquals("Alex", new PassExpiredEvent("Alex").getPlayerName());
        assertNotNull(PassRevokedEvent.getHandlerList());
        assertNotNull(PassExpiredEvent.getHandlerList());
    }

    @Test
    void luckPermsGroupNodeName() {
        assertEquals("group.donator", LuckPermsPassSync.groupNodeName("donator"));
        assertEquals("group.donator", LuckPermsPassSync.groupNodeName("  donator  "));
    }
}
